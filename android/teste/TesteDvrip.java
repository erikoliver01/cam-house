import com.erik.camhouse.direto.Dvrip;

import java.io.BufferedReader;
import java.io.InputStreamReader;

// Teste no PC do controle DVRIP do app, SEM mover a camera: hash, login, keepalive e um "parar" de PTZ.
// Uso: (linha 1 = usuario, linha 2 = senha pelo stdin) | java TesteDvrip <host>
public class TesteDvrip {
    public static void main(String[] a) throws Exception {
        if (!"tlJwpbo6".equals(Dvrip_hash(""))) throw new AssertionError("hash vazio errado");
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        String user = r.readLine(), senha = r.readLine();
        Dvrip d = new Dvrip();
        long t = System.currentTimeMillis();
        d.conectar(a[0], user, senha);
        System.out.println("login ok em " + (System.currentTimeMillis() - t) + " ms");
        System.out.println("keepalive Ret=" + d.keepalive());
        String si = d.sistema();
        System.out.println("sysinfo tem SoftWareVersion: " + si.contains("SoftWareVersion"));
        System.out.println("PTZ parar (nao move) Ret=" + d.ptz("DirectionLeft", false, 5));
        d.fechar();
    }

    static String Dvrip_hash(String s) throws Exception {
        java.lang.reflect.Method m = Dvrip.class.getDeclaredMethod("hash", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }
}
