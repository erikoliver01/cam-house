package com.erik.camhouse.direto;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

// Cliente RTSP minimo para a camera Xiongmai: RTP intercalado no TCP (passa bem pelo Tailscale),
// so a trilha de video H.265 (RFC 7798). Sem Android aqui dentro, para dar para testar no PC.
public class RtspHevc implements Runnable {
    public interface Saida {
        void nal(byte[] b, int off, int len, long ptsUs, boolean fimDoQuadro);
        void estado(String s);
        default void som(byte[] alaw, int off, int len) { }   // microfone da camera: G.711 A-law 8 kHz mono
    }

    private final String host, url;
    private final int porta;
    private final Saida saida;
    private volatile boolean parar;
    private Socket sock;
    private DataInputStream in;
    private OutputStream out;
    private int cseq;
    private String sessao;

    public RtspHevc(String host, int porta, String caminho, Saida saida) {
        this.host = host; this.porta = porta; this.saida = saida;
        this.url = "rtsp://" + host + ":" + porta + "/" + caminho;
    }

    public void parar() {
        parar = true;
        try { if (sock != null) sock.close(); } catch (IOException e) { }
    }

    @Override public void run() {
        try { rodar(); }
        catch (Exception e) { if (!parar) saida.estado("erro: " + e.getMessage()); }
        finally { parar(); }
    }

    private static class Resp { int codigo; Map<String, String> cab = new HashMap<>(); String corpo = ""; }

    private void rodar() throws IOException {
        saida.estado("conectando em " + host + "...");
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, porta), 6000);
        sock.setSoTimeout(15000);
        sock.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 1 << 16));
        out = sock.getOutputStream();

        Resp d = pedir("DESCRIBE", url, "Accept: application/sdp\r\n");
        if (d.codigo == 401) throw new IOException("login recusado pela camera (401)");
        if (d.codigo != 200) throw new IOException("DESCRIBE respondeu " + d.codigo);
        String base = d.cab.getOrDefault("content-base", url);
        if (!base.endsWith("/")) base += "/";

        // SDP: controle da trilha de video e, se vier, VPS/SPS/PPS prontos
        String controle = null, controleSom = null, vps = null, sps = null, pps = null;
        boolean video = false, audio = false, pcma = false;
        for (String l : d.corpo.split("\r?\n")) {
            if (l.startsWith("m=")) { video = l.startsWith("m=video"); audio = l.startsWith("m=audio"); }
            if (audio && l.startsWith("a=control:")) controleSom = l.substring(10).trim();
            if (audio && (l.startsWith("m=audio") && l.trim().endsWith(" 8") || l.toUpperCase().contains("PCMA/8000"))) pcma = true;
            if (!video) continue;
            if (l.startsWith("a=control:")) controle = l.substring(10).trim();
            if (l.startsWith("a=fmtp:")) {
                for (String p : l.substring(l.indexOf(' ') + 1).split(";")) {
                    String[] kv = p.trim().split("=", 2);
                    if (kv.length < 2) continue;
                    if (kv[0].equals("sprop-vps")) vps = kv[1];
                    if (kv[0].equals("sprop-sps")) sps = kv[1];
                    if (kv[0].equals("sprop-pps")) pps = kv[1];
                }
            }
        }
        if (controle == null) throw new IOException("a camera nao mandou trilha de video");
        String trilha = controle.startsWith("rtsp://") ? controle : base + controle;

        Resp s = pedir("SETUP", trilha, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        if (s.codigo != 200) throw new IOException("SETUP respondeu " + s.codigo);
        String sess = s.cab.get("session");
        sessao = sess == null ? null : sess.split(";")[0].trim();
        if (controleSom != null && pcma) {   // som na mesma conexao (canais 2-3); se a camera recusar, segue so com video
            Resp a = pedir("SETUP", controleSom.startsWith("rtsp://") ? controleSom : base + controleSom,
                "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n");
            if (a.codigo != 200) saida.estado("sem som (" + a.codigo + ")");
        }
        Resp p = pedir("PLAY", base, "Range: npt=0.000-\r\n");
        if (p.codigo != 200) throw new IOException("PLAY respondeu " + p.codigo);
        saida.estado("recebendo video...");

        for (String b64 : new String[] { vps, sps, pps }) {
            if (b64 == null) continue;
            byte[] n = Base64.getDecoder().decode(b64);
            saida.nal(n, 0, n.length, 0, false);
        }

        byte[] buf = new byte[1 << 16];
        long proximoAviso = System.currentTimeMillis() + 25000;
        while (!parar) {
            int c = in.read();
            if (c < 0) throw new IOException("a camera fechou a conexao");
            if (c == '$') {
                int canal = in.readUnsignedByte();
                int tam = in.readUnsignedShort();
                in.readFully(buf, 0, tam);
                if (canal == 0) rtp(buf, tam);
                else if (canal == 2 && tam > 12) {   // RTP do som: pula cabecalho (CSRC/extensao) e entrega o A-law
                    int p0 = 12 + 4 * (buf[0] & 0x0f);
                    if ((buf[0] & 0x10) != 0 && p0 + 4 <= tam) p0 += 4 + (((buf[p0 + 2] & 0xff) << 8) | (buf[p0 + 3] & 0xff)) * 4;
                    if (p0 < tam) saida.som(buf, p0, tam - p0);
                }
            } else if (c == 'R') {
                lerResposta('R');   // resposta ao keepalive, no meio do video
            }
            if (System.currentTimeMillis() > proximoAviso) {   // sem isso a camera derruba a sessao em ~60 s
                enviar("GET_PARAMETER", base, "");
                proximoAviso = System.currentTimeMillis() + 25000;
            }
        }
    }

    private void enviar(String metodo, String alvo, String extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(metodo).append(' ').append(alvo).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(++cseq).append("\r\n");
        sb.append("User-Agent: CAMhouse\r\n");
        if (sessao != null) sb.append("Session: ").append(sessao).append("\r\n");
        sb.append(extra).append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private Resp pedir(String metodo, String alvo, String extra) throws IOException {
        enviar(metodo, alvo, extra);
        int c;
        while ((c = in.read()) == '$') {   // pacote de midia antes da resposta: descarta
            in.readUnsignedByte();
            in.skipBytes(in.readUnsignedShort());
        }
        if (c < 0) throw new IOException("a camera fechou a conexao");
        return lerResposta(c);
    }

    private Resp lerResposta(int primeiro) throws IOException {
        Resp r = new Resp();
        String status = (char) primeiro + linha();
        String[] partes = status.split(" ");
        r.codigo = partes.length > 1 ? Integer.parseInt(partes[1]) : 0;
        String l;
        while (!(l = linha()).isEmpty()) {
            int i = l.indexOf(':');
            if (i > 0) r.cab.put(l.substring(0, i).trim().toLowerCase(), l.substring(i + 1).trim());
        }
        int tam = Integer.parseInt(r.cab.getOrDefault("content-length", "0"));
        if (tam > 0) {
            byte[] b = new byte[tam];
            in.readFully(b);
            r.corpo = new String(b, StandardCharsets.UTF_8);
        }
        return r;
    }

    private String linha() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') b.write(c);
        if (c < 0) throw new IOException("a camera fechou a conexao");
        return b.toString("US-ASCII");
    }

    // ---- RTP -> NALs H.265 ----
    private final ByteArrayOutputStream fu = new ByteArrayOutputStream();
    private boolean fuOk;
    private int ultimaSeq = -1;
    private long ts0 = -1;

    private void rtp(byte[] b, int tam) {
        if (tam < 14) return;
        int cc = b[0] & 0x0f;
        boolean marca = (b[1] & 0x80) != 0;
        int seq = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        long ts = ((long) (b[4] & 0xff) << 24) | ((b[5] & 0xff) << 16) | ((b[6] & 0xff) << 8) | (b[7] & 0xff);
        int p = 12 + 4 * cc;
        if ((b[0] & 0x10) != 0) p += 4 + (((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff)) * 4;
        if ((b[0] & 0x20) != 0) tam -= b[tam - 1] & 0xff;
        if (p + 2 > tam) return;
        boolean perdeu = ultimaSeq >= 0 && seq != ((ultimaSeq + 1) & 0xffff);
        ultimaSeq = seq;
        if (ts0 < 0) ts0 = ts;
        long pts = (((ts - ts0) & 0xffffffffL) * 1000) / 90;   // relogio de 90 kHz -> microssegundos

        int tipo = (b[p] >> 1) & 0x3f;
        if (tipo == 48) {   // AP: varios NALs pequenos no mesmo pacote
            int q = p + 2;
            while (q + 2 <= tam) {
                int n = ((b[q] & 0xff) << 8) | (b[q + 1] & 0xff);
                q += 2;
                if (q + n > tam) break;
                saida.nal(b, q, n, pts, marca && q + n >= tam);
                q += n;
            }
        } else if (tipo == 49) {   // FU: um NAL grande quebrado em pedacos
            if (p + 3 > tam) return;
            int h = b[p + 2] & 0xff;
            if ((h & 0x80) != 0) {
                fu.reset();
                fu.write((b[p] & 0x81) | ((h & 0x3f) << 1));
                fu.write(b[p + 1]);
                fuOk = true;
            } else if (perdeu) {
                fuOk = false;   // pedaco perdido: descarta o NAL inteiro
            }
            if (!fuOk) return;
            fu.write(b, p + 3, tam - p - 3);
            if ((h & 0x40) != 0) {
                byte[] n = fu.toByteArray();
                saida.nal(n, 0, n.length, pts, marca);
                fuOk = false;
            }
        } else {
            saida.nal(b, p, tam - p, pts, marca);
        }
    }
}
