package com.erik.camhouse.direto;

// Conta dos sons que a camera toca: sem nada de Android, para dar para testar no PC (teste/TesteSons.java)
public final class Sons {
    public static final int TAXA = 8000, MAX_SEG = 60;

    // "alarme-nuclear.al" -> "Alarme nuclear" (nome do botao)
    public static String rotulo(String arquivo) {
        String s = arquivo.endsWith(".al") ? arquivo.substring(0, arquivo.length() - 3) : arquivo;
        s = s.replace('-', ' ').replace('_', ' ').trim();
        return s.isEmpty() ? "Som" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // mono 16 bits em qualquer taxa -> 8 kHz: media ao reduzir (evita chiado), interpolacao ao aumentar
    public static short[] reamostrar(short[] pcm, int n, int taxa) {
        int m = (int) Math.min((long) n * TAXA / taxa, MAX_SEG * TAXA);
        short[] s = new short[m];
        double passo = (double) taxa / TAXA;
        for (int i = 0; i < m; i++) {
            double ini = i * passo;
            if (passo > 1) {
                int a = (int) ini, b = Math.min(n, Math.max(a + 1, (int) (ini + passo)));
                long soma = 0;
                for (int k = a; k < b; k++) soma += pcm[k];
                s[i] = (short) (soma / (b - a));
            } else {
                int a = (int) ini, b = Math.min(n - 1, a + 1);
                double f = ini - a;
                s[i] = (short) Math.round(pcm[a] * (1 - f) + pcm[b] * f);
            }
        }
        return s;
    }

    // leva o pico a 90% do volume maximo; silencio fica como esta
    public static short[] normalizar(short[] pcm) {
        int pico = 0;
        for (short v : pcm) pico = Math.max(pico, Math.abs((int) v));
        if (pico == 0) return pcm;
        double g = 29490.0 / pico;
        short[] s = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) s[i] = (short) Math.round(pcm[i] * g);
        return s;
    }

    public static byte[] paraAlaw(short[] pcm) {
        byte[] a = new byte[pcm.length];
        for (int i = 0; i < pcm.length; i++) a[i] = Alaw.codifica(pcm[i]);
        return a;
    }

    // nome do arquivo escolhido -> nome seguro para guardar na lista do app
    public static String nomeSeguro(String s) {
        if (s == null) return "Som";
        int p = s.lastIndexOf('.');
        if (p > 0) s = s.substring(0, p);
        s = s.replaceAll("[^\\p{L}\\p{N} _-]", "").trim();
        if (s.length() > 40) s = s.substring(0, 40).trim();
        return s.isEmpty() ? "Som" : s;
    }
}
