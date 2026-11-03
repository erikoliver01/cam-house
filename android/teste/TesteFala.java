import com.erik.camhouse.direto.Dvrip;

import java.io.BufferedReader;
import java.io.InputStreamReader;

// Teste no PC da abertura da fala (OPTalk Claim + Start) SEM mandar som: nada toca no alto-falante.
// Uso: (usuario e senha pelo stdin, uma linha cada) | java TesteFala <host>
public class TesteFala {
    public static void main(String[] a) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        String user = r.readLine(), senha = r.readLine();
        Dvrip d = new Dvrip();
        d.conectar(a[0], user, senha);
        d.abrirFala();
        System.out.println("OPTalk Claim aceito e Start enviado; fechando sem mandar som");
        Thread.sleep(500);
        d.fechar();
        Dvrip d2 = new Dvrip();   // a camera continua aceitando login depois (a fala nao travou nada)
        d2.conectar(a[0], user, senha);
        System.out.println("novo login depois da fala: keepalive Ret=" + d2.keepalive());
        d2.fechar();
    }
}
