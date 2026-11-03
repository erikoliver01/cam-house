import com.erik.camhouse.direto.RtspHevc;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

// Teste no PC do cliente RTSP do app: grava N segundos de H.265 (Annex B) para conferir com o ffprobe.
// Uso: echo <caminho rtsp sem host> | java TestePC <host> <segundos> <arquivo.h265>
public class TestePC {
    public static void main(String[] a) throws Exception {
        String caminho = new BufferedReader(new InputStreamReader(System.in)).readLine().trim();
        FileOutputStream f = new FileOutputStream(a[2]);
        long[] cont = new long[3];   // NALs, quadros, ms ate o 1o NAL
        long t0 = System.currentTimeMillis();
        RtspHevc r = new RtspHevc(a[0], 554, caminho, new RtspHevc.Saida() {
            public void nal(byte[] b, int off, int len, long pts, boolean fim) {
                try { f.write(new byte[] { 0, 0, 0, 1 }); f.write(b, off, len); } catch (Exception e) { throw new RuntimeException(e); }
                if (cont[0]++ == 0) cont[2] = System.currentTimeMillis() - t0;
                if (fim) cont[1]++;
            }
            public void estado(String s) { System.out.println("estado: " + s); }
        });
        Thread t = new Thread(r);
        t.start();
        Thread.sleep(Long.parseLong(a[1]) * 1000);
        r.parar();
        t.join(3000);
        f.close();
        System.out.println("NALs=" + cont[0] + " quadros=" + cont[1] + " 1o NAL em " + cont[2] + " ms");
    }
}
