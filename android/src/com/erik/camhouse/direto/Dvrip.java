package com.erik.camhouse.direto;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Controle da camera Xiongmai pelo protocolo DVRIP (porta 34567): login, PTZ e presets.
// Mesmo formato do xmcam.js do PC (verificado na camera). Sem Android aqui, para testar no PC.
public class Dvrip {
    static final int LOGIN = 1000, KEEPALIVE = 1006, SYSINFO = 1020, PTZ = 1400;

    private Socket sock;
    private DataInputStream in;
    private OutputStream out;
    private int sessao, seq;

    public synchronized void conectar(String host, String user, String senha) throws IOException {
        fechar();
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, 34567), 5000);
        sock.setSoTimeout(6000);
        sock.setTcpNoDelay(true);
        in = new DataInputStream(sock.getInputStream());
        out = sock.getOutputStream();
        sessao = 0;
        seq = 0;
        String r = enviar(LOGIN, "{\"EncryptType\":\"MD5\",\"LoginType\":\"DVRIP-Web\",\"PassWord\":\"" + hash(senha)
            + "\",\"UserName\":" + texto(user) + "}");
        Matcher s = Pattern.compile("\"SessionID\"\\s*:\\s*\"0x([0-9A-Fa-f]+)\"").matcher(r);
        if (s.find()) sessao = (int) Long.parseLong(s.group(1), 16);
        int ret = ret(r);
        if (ret != 100) { fechar(); throw new IOException(ret == 203 || ret == 205 ? "usuario ou senha errados (Ret " + ret + ")" : "login falhou (Ret " + ret + ")"); }
    }

    public synchronized boolean conectado() { return sock != null && !sock.isClosed(); }

    public synchronized void fechar() {
        try { if (sock != null) sock.close(); } catch (IOException e) { }
        sock = null;
    }

    String sid() { return String.format("0x%08X", sessao); }

    public int keepalive() throws IOException {
        return ret(enviar(KEEPALIVE, "{\"Name\":\"KeepAlive\",\"SessionID\":\"" + sid() + "\"}"));
    }

    public String sistema() throws IOException {
        return enviar(SYSINFO, "{\"Name\":\"SystemInfo\",\"SessionID\":\"" + sid() + "\"}");
    }

    // configuracao da camera (blocos como "Camera.WhiteLight"): resposta inteira em JSON
    public String lerConfig(String bloco) throws IOException {
        return enviar(1042, "{\"Name\":" + texto(bloco) + ",\"SessionID\":\"" + sid() + "\"}");
    }

    // valorJson = o bloco inteiro ja alterado (a camera so aceita o bloco completo)
    public int gravarConfig(String bloco, String valorJson) throws IOException {
        return ret(enviar(1040, "{\"Name\":" + texto(bloco) + ",\"SessionID\":\"" + sid() + "\"," + texto(bloco) + ":" + valorJson + "}"));
    }

    // cmd: DirectionUp/Down/Left/Right, ZoomTile (+) ou ZoomWide (-). Gira ate chegar o parar (Preset -1).
    public int ptz(String cmd, boolean comecar, int passo) throws IOException {
        return ret(enviar(PTZ, opPtz(cmd, comecar ? 65535 : -1, passo, "SetBegin")));
    }

    public int irPreset(int n) throws IOException {
        return ret(enviar(PTZ, opPtz("GotoPreset", n, 5, "SetBegin")));
    }

    String opPtz(String cmd, int preset, int passo, String padrao) {
        return "{\"Name\":\"OPPTZControl\",\"SessionID\":\"" + sid() + "\",\"OPPTZControl\":{\"Command\":\"" + cmd
            + "\",\"Parameter\":{\"AUX\":{\"Number\":0,\"Status\":\"On\"},\"Channel\":0,\"MenuOpts\":\"Enter\","
            + "\"POINT\":{\"bottom\":0,\"left\":0,\"right\":0,\"top\":0},\"Pattern\":\"" + padrao + "\",\"Preset\":" + preset
            + ",\"Step\":" + passo + ",\"Tour\":0}}}";
    }

    // ---- falar pela camera: OPTalk Claim 1434, Start 1430, dados 1432 (igual ao server.js / go2rtc pkg/dvrip) ----
    public void abrirFala() throws IOException {
        int r = ret(enviar(1434, opTalk("Claim")));
        if (r != 100) throw new IOException("a camera recusou o audio (Ret " + r + ")");
        escrever(1430, (opTalk("Start") + "\n\0").getBytes(StandardCharsets.UTF_8));   // responde sem JSON: nao espera
    }

    String opTalk(String acao) {
        return "{\"Name\":\"OPTalk\",\"SessionID\":\"" + sid() + "\",\"OPTalk\":{\"Action\":\"" + acao
            + "\",\"AudioFormat\":{\"EncodeType\":\"G711_ALAW\"}}}";
    }

    // 40 ms de voz: 320 bytes A-law 8 kHz, com o cabecalho de midia 00 00 01 FA 0E 02 + tamanho
    public void falar(byte[] alaw, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(8 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] { 0, 0, 1, (byte) 0xFA, 0x0E, 0x02 });
        b.putShort((short) len);
        b.put(alaw, 0, len);
        escrever(1432, b.array());
        if (in.available() > 0) in.skip(in.available());   // descarta respostas que a camera mandar no meio
    }

    synchronized void escrever(int msg, byte[] corpo) throws IOException {
        if (sock == null) throw new IOException("sem conexao com a camera");
        ByteBuffer h = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        h.put(0, (byte) 0xFF);
        h.putInt(4, sessao);
        h.putInt(8, seq++);
        h.putShort(14, (short) msg);
        h.putInt(16, corpo.length);
        try {
            out.write(h.array());
            out.write(corpo);
            out.flush();
        } catch (IOException e) {
            fechar();
            throw e;
        }
    }

    // um pedido, uma resposta (a camera responde na ordem)
    synchronized String enviar(int msg, String json) throws IOException {
        if (sock == null) throw new IOException("sem conexao com a camera");
        byte[] corpo = (json + "\n\0").getBytes(StandardCharsets.UTF_8);
        ByteBuffer h = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        h.put(0, (byte) 0xFF);
        h.putInt(4, sessao);
        h.putInt(8, seq++);
        h.putShort(14, (short) msg);
        h.putInt(16, corpo.length);
        try {
            out.write(h.array());
            out.write(corpo);
            out.flush();
            byte[] cab = new byte[20];
            in.readFully(cab);
            int tam = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN).getInt(16);
            if (tam < 0 || tam > 1 << 20) throw new IOException("resposta estranha da camera");
            byte[] r = new byte[tam];
            in.readFully(r);
            return new String(r, StandardCharsets.UTF_8).replaceAll("[\\n\\x00]+$", "");
        } catch (IOException e) {
            fechar();
            throw e;
        }
    }

    static int ret(String json) {
        Matcher m = Pattern.compile("\"Ret\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static String texto(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // Hash de senha da Xiongmai: MD5 dobrado em 8 caracteres (igual ao sofiaHash do xmcam.js)
    static String hash(String senha) {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(senha.getBytes(StandardCharsets.UTF_8));
            StringBuilder o = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                int n = ((md5[2 * i] & 0xff) + (md5[2 * i + 1] & 0xff)) % 62;
                o.append((char) (n < 10 ? 48 + n : n < 36 ? 65 + n - 10 : 97 + n - 36));
            }
            return o.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
