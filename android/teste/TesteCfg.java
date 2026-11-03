import com.erik.camhouse.direto.Dvrip;

import java.io.BufferedReader;
import java.io.InputStreamReader;

// Teste no PC: le (sem gravar) os blocos de configuracao que a tela de ajustes do app usa.
// Uso: (usuario e senha pelo stdin, uma linha cada) | java TesteCfg <host>
public class TesteCfg {
    public static void main(String[] a) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        String user = r.readLine(), senha = r.readLine();
        Dvrip d = new Dvrip();
        d.conectar(a[0], user, senha);
        for (String b : new String[] { "Camera.WhiteLight", "Detect.DetectTrack", "Detect.MotionDetect", "Detect.HumanDetection", "Camera.Param", "fVideo.Volume" }) {
            String j = d.lerConfig(b);
            boolean ok = j.replace(" ", "").contains("\"Ret\":100");
            System.out.println(b + ": " + (ok ? "Ret 100" : "falhou") + " | " + j.length() + " bytes | tem o bloco: " + j.contains("\"" + b + "\""));
        }
        d.fechar();
    }
}
