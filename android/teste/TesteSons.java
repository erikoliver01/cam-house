import com.erik.camhouse.direto.Sons;

// Roda no PC, sem camera nem Android: javac -d x Sons.java Alaw.java TesteSons.java; java -ea -cp x TesteSons
public class TesteSons {
    public static void main(String[] a) {
        short[] tom = new short[48000];   // 1 s de 440 Hz a 48 kHz
        for (int i = 0; i < tom.length; i++) tom[i] = (short) (Math.sin(2 * Math.PI * 440 * i / 48000) * 8000);
        short[] r = Sons.reamostrar(tom, tom.length, 48000);
        assert r.length == 8000 : r.length;
        int picoR = 0;
        for (short v : r) picoR = Math.max(picoR, Math.abs((int) v));
        assert picoR > 7000 && picoR <= 8000 : picoR;   // reduzir nao pode apagar o som

        short[] sobe = Sons.reamostrar(new short[] { 0, 1000 }, 2, 4000);   // de 4 kHz para 8 kHz
        assert sobe.length == 4 && sobe[0] == 0 && sobe[1] == 500 && sobe[2] == 1000 : java.util.Arrays.toString(sobe);

        short[] longo = new short[8000 * 90];
        assert Sons.reamostrar(longo, longo.length, 8000).length == 8000 * Sons.MAX_SEG;

        short[] n = Sons.normalizar(new short[] { 100, -1000, 500 });
        assert n[1] == -29490 && n[0] == 2949 : java.util.Arrays.toString(n);
        short[] sil = new short[10];
        assert Sons.normalizar(sil) == sil;
        assert Sons.paraAlaw(new short[5]).length == 5;

        assert Sons.nomeSeguro("../../meu som!.mp3").equals("meu som");
        assert Sons.nomeSeguro("...").equals("Som") && Sons.nomeSeguro(null).equals("Som");
        assert Sons.nomeSeguro("Café_ação-1.ogg").equals("Café_ação-1");
        assert Sons.rotulo("alarme-nuclear.al").equals("Alarme nuclear") && Sons.rotulo(".al").equals("Som");
        System.out.println("ok");
    }
}
