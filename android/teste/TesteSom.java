import com.erik.camhouse.direto.Alaw;
import com.erik.camhouse.direto.RtspHevc;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

// Teste no PC: confere o codec A-law e grava N segundos do microfone da camera (A-law cru) para o ffprobe.
// Uso: echo <caminho rtsp> | java TesteSom <host> <segundos> <arquivo.alaw>
public class TesteSom {
    public static void main(String[] a) throws Exception {
        // ida e volta do codec: o erro tem que ser pequeno (quantizacao do A-law)
        int pior = 0;
        for (int v = -32768; v <= 32767; v += 7) {
            int volta = Alaw.decodifica(Alaw.codifica((short) v));
            int erro = Math.abs(volta - v), lim = Math.max(16, Math.abs(v) / 16);
            if (erro > lim) throw new AssertionError("codec errado em " + v + " -> " + volta);
            pior = Math.max(pior, erro);
        }
        System.out.println("codec A-law ok (maior erro " + pior + ")");
        String caminho = new BufferedReader(new InputStreamReader(System.in)).readLine().trim();
        FileOutputStream f = new FileOutputStream(a[2]);
        long[] n = new long[2];
        RtspHevc r = new RtspHevc(a[0], 554, caminho, new RtspHevc.Saida() {
            public void nal(byte[] b, int off, int len, long pts, boolean fim) { n[1]++; }
            public void estado(String s) { System.out.println("estado: " + s); }
            public void som(byte[] b, int off, int len) {
                try { f.write(b, off, len); } catch (Exception e) { throw new RuntimeException(e); }
                n[0] += len;
            }
        });
        Thread t = new Thread(r);
        t.start();
        Thread.sleep(Long.parseLong(a[1]) * 1000);
        r.parar();
        t.join(3000);
        f.close();
        System.out.println("som: " + n[0] + " bytes (" + (n[0] / 8) + " ms) · NALs de video: " + n[1]);
    }
}
