package com.erik.camhouse.direto;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// CAM house direto (teste): video e controle direto da camera, sem o PC. Visual igual ao public/index.html.
// Em casa pelo Wi-Fi; fora, pelo Tailscale (ponte no Moto G). Login e senha ficam so neste celular.
public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    // cores do CAM house (variaveis --bg, --panel... do index.html); mudam com o tema escolhido em Ajustes > Temas
    static int BG, PANEL, PANEL2, LINHA, LINHA_SUAVE, G2, OK, RUIM, FG, MUT, INFO, ACENTO, DIALOGO, TOPO_FG, BTN_FG, tema;
    static int[] GRAD, TOPO;   // GRAD = botoes, TOPO = heroi e barra da gravacao
    static final String[] TEMAS = { "Neon", "Futurista", "Preto e vermelho" };
    static final int FUTURISTA = 1, VERMELHO = 2;
    // BG, PANEL, PANEL2, LINHA, LINHA_SUAVE, G2, OK, RUIM, FG, MUT, INFO, ACENTO, DIALOGO, TOPO_FG, BTN_FG, GRAD 0..2, TOPO 0..2
    static final int[][] CORES = {
        { 0xFF1A0726, 0xFF25103A, 0xFF30164A, 0xFF6B4A8F, 0xFF3F2560, 0xFFFF1FC8, 0xFF3DDC97, 0xFFFF4D6D, 0xFFF4ECFF, 0xFFA993C4, 0xFF38B6FF, 0xFFFF6AD5, 0xFF25103A, 0xFFFFFFFF, 0xFFFFFFFF,
          0xFFFF6AD5, 0xFFFF1FC8, 0xFFA10F9A, 0xFFFF6AD5, 0xFFFF1FC8, 0xFFA10F9A },
        // paineis translucidos para a grade do fundo aparecer; DIALOGO e solido porque a janela do dialogo e transparente
        { 0xFF03100F, 0x0F00E5FF, 0x1F00E5FF, 0x7300E5FF, 0x4000E5FF, 0xFF00E5FF, 0xFF2BFFB0, 0xFFFF4D6D, 0xFFD8FBFF, 0xFF5FB8BF, 0xFF00E5FF, 0xFF00E5FF, 0xFF061A1F, 0xFF00E5FF, 0xFF012326,
          0xFF00E5FF, 0xFF00E5FF, 0xFF00E5FF, 0x2900E5FF, 0x1A00E5FF, 0x0A00E5FF },
        { 0xFF0A0A0B, 0xFF151517, 0xFF1F1F22, 0xFF3A1418, 0xFF261013, 0xFFFF2A3A, 0xFF3DDC97, 0xFFFF2A3A, 0xFFF3F0F0, 0xFF9B8F90, 0xFFFF6B70, 0xFFFF2A3A, 0xFF151517, 0xFFFFFFFF, 0xFFFFFFFF,
          0xFFE01422, 0xFFE01422, 0xFFE01422, 0xFFD0101F, 0xFFA50A14, 0xFF7A0711 },
    };
    static { aplicarTema(0); }
    static void aplicarTema(int t) {
        tema = t >= 0 && t < CORES.length ? t : 0;
        int[] c = CORES[tema];
        BG = c[0]; PANEL = c[1]; PANEL2 = c[2]; LINHA = c[3]; LINHA_SUAVE = c[4]; G2 = c[5]; OK = c[6]; RUIM = c[7];
        FG = c[8]; MUT = c[9]; INFO = c[10]; ACENTO = c[11]; DIALOGO = c[12]; TOPO_FG = c[13]; BTN_FG = c[14];
        GRAD = new int[] { c[15], c[16], c[17] };
        TOPO = new int[] { c[18], c[19], c[20] };
    }
    static int alfa(int a, int cor) { return (a << 24) | (cor & 0xFFFFFF); }
    static final String[] NOMES = { "Porta de entrada", "Câmera 2" };
    static final String[] IP_PADRAO = { "", "" };   // preencha o IP de cada camera no ⚙ do app
    static final String[][] PRESETS = { { "", "", "" }, { "", "", "" } };   // nomes opcionais dos presets 1-3 de cada camera

    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());
    Handler rede;   // fila unica para o DVRIP: um comando por vez, fora da tela
    final Dvrip dvrip = new Dvrip();

    LinearLayout pagina, blocoVideo, blocoPtz, blocoSom, blocoAjustes, presets;
    View heroi, abas, ferramentas;
    ScrollView rolagem;
    FrameLayout palco, flutuante;
    TextureView tela;
    TextView chipVivo, chipZoom, numFps, numImagem, numCam, ptzMsg;
    View bolinha, pontoVivo;
    Button[] botaoCam = new Button[2];
    Button botaoImagem;
    Surface superficie;
    RtspHevc rtsp;
    Decodificador dec;
    int geracao, vidW = 16, vidH = 9;
    boolean cheia;
    float zoom = 1, panX, panY;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("camera", MODE_PRIVATE);
        aplicarTema(prefs.getInt("tema", 0));
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        migrarV1();
        HandlerThread t = new HandlerThread("dvrip");
        t.start();
        rede = new Handler(t.getLooper());
        montarTela();
        if (user(cam()).isEmpty()) ui.post(this::ajustes);
        else conectarControle();
    }

    // a versao 1 guardava uma camera so; vira a camera 0 (Porta)
    void migrarV1() {
        if (prefs.contains("user") && !prefs.contains("user0"))
            prefs.edit().putString("host0", prefs.getString("host", IP_PADRAO[0])).putString("user0", prefs.getString("user", ""))
                .putString("pass0", prefs.getString("pass", "")).remove("host").remove("user").remove("pass").apply();
    }

    int cam() { return prefs.getInt("cam", 0); }
    String host(int i) { return prefs.getString("host" + i, IP_PADRAO[i]); }
    String user(int i) { return prefs.getString("user" + i, ""); }
    String pass(int i) { return prefs.getString("pass" + i, ""); }
    int stream() { return prefs.getInt("stream", 1); }

    // ---------- fontes e desenho ----------
    final Map<Integer, Typeface> fontes = new HashMap<>();
    // Lexend (fonte da pagina) em assets/fonts; sem ela, a fonte do sistema
    Typeface fonte(int peso) {
        Typeface t = fontes.get(peso);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(getAssets(), "fonts/Lexend.ttf").setFontVariationSettings("'wght' " + peso).build();
        } catch (Exception e) { t = null; }
        if (t == null) t = Typeface.create(Typeface.SANS_SERIF, peso >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        fontes.put(peso, t);
        return t;
    }

    // titulos: fonte propria de cada tema (licencas em assets/fonts); o corpo do texto segue em Lexend
    Typeface fonteTitulo(int peso) {
        Typeface t = fontes.get(peso + 1000);
        if (t != null) return t;
        String nome = tema == FUTURISTA ? "QuantumFuturistic.ttf" : tema == VERMELHO ? "BebasNeue.ttf" : null;
        try { if (nome != null) t = Typeface.createFromAsset(getAssets(), "fonts/" + nome); } catch (Exception e) { t = null; }
        if (t == null) t = fonte(peso);
        fontes.put(peso + 1000, t);
        return t;
    }

    // numeros grandes: monoespacado, ou Orbitron com brilho no Futurista
    void numerico(TextView v) {
        if (tema == FUTURISTA) {
            v.setTypeface(fonteTitulo(700));
            v.setShadowLayer(dp(6), 0, 0, alfa(0x99, ACENTO));
        } else v.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    float cantos(float raio) {
        return tema == VERMELHO ? (raio >= 999 ? 8 : Math.min(raio, 10)) : tema == FUTURISTA ? Math.min(raio, 2) : raio;
    }

    abstract static class Desenho extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // Futurista: canto superior esquerdo e inferior direito cortados em diagonal
    class Chanfro extends Desenho {
        final int cor, borda; final float lado;
        Chanfro(int cor, int borda, float lado) { this.cor = cor; this.borda = borda; this.lado = lado; }
        @Override public void draw(Canvas c) {
            android.graphics.Rect r = getBounds();
            float m = borda != 0 ? lado / 2 : 0, e = r.left + m, t = r.top + m, d = r.right - m, b = r.bottom - m;
            float k = Math.min(dp(10), Math.min(r.width(), r.height()) / 3f);
            android.graphics.Path h = new android.graphics.Path();
            h.moveTo(e + k, t); h.lineTo(d, t); h.lineTo(d, b - k); h.lineTo(d - k, b); h.lineTo(e, b); h.lineTo(e, t + k); h.close();
            if ((cor >>> 24) != 0) { p.setStyle(Paint.Style.FILL); p.setColor(cor); c.drawPath(h, p); }
            if (borda != 0) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(lado); p.setColor(borda); c.drawPath(h, p); }
        }
    }

    // Futurista: grade fina no fundo
    class GradeHud extends Desenho {
        @Override public void draw(Canvas c) {
            android.graphics.Rect r = getBounds();
            c.drawColor(BG);
            p.setStrokeWidth(1);
            p.setColor(alfa(0x0D, ACENTO));
            int passo = dp(24);
            for (int x = r.left; x < r.right; x += passo) c.drawLine(x, r.top, x, r.bottom, p);
            for (int y = r.top; y < r.bottom; y += passo) c.drawLine(r.left, y, r.right, y, p);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    // Futurista: anel do topo. shortcut: parado, a previa gira; animar pede parar o Animator ao trocar de tema
    class AnelHud extends Desenho {
        @Override public void draw(Canvas c) {
            android.graphics.RectF o = new android.graphics.RectF(getBounds());
            float m = dp(1);
            o.inset(m, m);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(alfa(0x99, ACENTO));
            c.drawArc(o, -90, 180, false, p);
            p.setColor(alfa(0x33, ACENTO));
            c.drawArc(o, 90, 90, false, p);
        }
    }

    Drawable fundoTela() { return tema == FUTURISTA ? new GradeHud() : new ColorDrawable(BG); }

    // circulos e chips sobre o video: redondos em todos os temas
    GradientDrawable redondo(int cor, int borda, float largura) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor);
        g.setCornerRadius(dp(999));
        if (borda != 0) g.setStroke(dp(largura), borda);
        return g;
    }

    Drawable caixa(int cor, float raio, int borda, float largura) {
        if (tema == FUTURISTA && raio > 0) return new Chanfro(cor, borda, dp(largura));
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor);
        g.setCornerRadius(dp(cantos(raio)));
        if (borda != 0) g.setStroke(dp(largura), borda);
        return g;
    }

    Drawable degrade(float raio) {   // var(--grad): 160deg g1 -> g2 55% -> g3; raio 999 = botao, o resto = topo
        boolean botao = raio >= 999;
        if (tema == FUTURISTA) return botao ? new Chanfro(GRAD[0], 0, 0) : new Chanfro(TOPO[0], LINHA, dp(1));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, botao ? GRAD : TOPO);
        g.setCornerRadius(dp(cantos(raio)));
        return g;
    }

    // botao de escolha: o texto acompanha o fundo (no Futurista o botao ligado e ciano claro)
    void marcaBtn(Button b, boolean on) {
        b.setBackground(on ? degrade(999) : caixa(PANEL2, 999, 0, 0));
        b.setTextColor(on ? BTN_FG : FG);
    }

    Drawable centroPtz() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, GRAD);
        g.setShape(GradientDrawable.OVAL);
        if (tema != FUTURISTA) return g;
        GradientDrawable anel = new GradientDrawable();
        anel.setShape(GradientDrawable.OVAL);
        anel.setStroke(dp(1.5f), ACENTO);
        LayerDrawable l = new LayerDrawable(new Drawable[] { anel, g });
        l.setLayerInset(1, dp(4), dp(4), dp(4), dp(4));
        return l;
    }

    Drawable cartaoNumero() {
        if (tema == FUTURISTA) return caixa(PANEL, 12, LINHA, 1);
        LayerDrawable l = new LayerDrawable(new Drawable[] { caixa(ACENTO, 8, 0, 0), caixa(PANEL, 8, 0, 0) });   // traco vermelho no topo
        l.setLayerInset(1, 0, dp(2), 0, 0);
        return l;
    }

    TextView texto(String s, float sp, int peso, int cor) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(fonte(peso));
        t.setTextColor(cor);
        t.setIncludeFontPadding(false);
        return t;
    }

    // titulos em caixa alta espacada (h2 e rotulos da pagina)
    TextView rotulo(String s, float sp, int cor, float espaco) {
        TextView t = texto(s.toUpperCase(Locale.ROOT), sp, 700, cor);
        t.setTypeface(fonteTitulo(700));
        if (tema == VERMELHO) t.setTextSize(sp * 1.25f);   // Bebas Neue e estreita
        t.setLetterSpacing(espaco);
        return t;
    }

    Button pilula(String s, boolean cheio, View.OnClickListener c) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTypeface(fonte(cheio ? 600 : 500));
        b.setTextSize(13);
        b.setTextColor(FG);
        b.setBackground(cheio ? caixa(PANEL2, 999, 0, 0) : caixa(Color.TRANSPARENT, 999, LINHA, 1));
        b.setStateListAnimator(null);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        if (c != null) b.setOnClickListener(c);
        return b;
    }

    LinearLayout.LayoutParams lp(int w, int h, float peso, int esq, int topo, int dir, int baixo) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h, peso);
        l.setMargins(dp(esq), dp(topo), dp(dir), dp(baixo));
        return l;
    }

    LinearLayout linha(int gravidade) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(gravidade);
        return l;
    }

    LinearLayout coluna() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    // ---------- tela ----------
    void montarTela() {
        rolagem = new ScrollView(this);
        rolagem.setBackground(fundoTela());
        rolagem.setFillViewport(true);
        pagina = coluna();
        pagina.setPadding(dp(16), dp(12), dp(16), dp(16));
        rolagem.addView(pagina);

        heroi = montarHeroi();
        pagina.addView(heroi);
        blocoVideo = montarBlocoVideo();
        pagina.addView(blocoVideo, lp(-1, -2, 0, 0, 16, 0, 0));
        blocoPtz = montarBlocoPtz();
        pagina.addView(blocoPtz, lp(-1, -2, 0, 0, 16, 0, 0));
        blocoSom = montarBlocoSom();
        pagina.addView(blocoSom, lp(-1, -2, 0, 0, 16, 0, 0));
        blocoClipes = montarBlocoClipes();
        pagina.addView(blocoClipes, lp(-1, -2, 0, 0, 16, 0, 0));
        blocoAjustes = montarBlocoAjustes();
        pagina.addView(blocoAjustes, lp(-1, -2, 0, 0, 16, 0, 0));
        TextView rodape = texto("", 11, 300, MUT);   // "CAM house v0.7 · feito por erikoliver01" -> abre o Sobre
        rodape.setText("CAM house v" + versao() + " · feito por erikoliver01 · Sobre");
        rodape.setGravity(Gravity.CENTER);
        rodape.setPadding(0, dp(18), 0, dp(6));
        rodape.setOnClickListener(v -> sobre());
        pagina.addView(rodape, new LinearLayout.LayoutParams(-1, -2));

        setContentView(rolagem);
        palco.post(this::ajustarAlturaVideo);
        atualizarBotoes();
    }

    // cartao do topo: degrade magenta, logo com bolinha de status, "CAM house" e numeros
    View montarHeroi() {
        LinearLayout h = coluna();
        h.setBackground(degrade(18));
        h.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout marca = linha(Gravity.CENTER_VERTICAL);
        FrameLayout logo = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.ic_camera_fg);
        img.setBackground(redondo(BG, TOPO_FG, 4));
        img.setPadding(dp(5), dp(5), dp(5), dp(5));
        logo.addView(img, new FrameLayout.LayoutParams(dp(48), dp(48)));
        bolinha = new View(this);
        bolinha.setBackground(redondo(0xFF777777, G2, 3));
        logo.addView(bolinha, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM | Gravity.END));
        marca.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout nome = coluna();
        ImageView wm = new ImageView(this);   // "CAM" Lemon Milk + "house" Rasley, desenhado com as fontes da pagina
        wm.setImageResource(R.drawable.wordmark);
        if (tema == FUTURISTA) wm.setColorFilter(ACENTO);
        wm.setAdjustViewBounds(true);
        wm.setScaleType(ImageView.ScaleType.FIT_START);
        nome.addView(wm, new LinearLayout.LayoutParams(-2, dp(40)));
        TextView sub = texto("Desenvolvido por erikoliver01", 9, 500, TOPO_FG);
        sub.setTypeface(fonte(500));
        sub.setAlpha(0.85f);
        nome.addView(sub, lp(-2, -2, 0, 0, 3, 0, 0));
        marca.addView(nome, lp(0, -2, 1, 12, 0, 0, 0));
        Button aj = new Button(this);
        aj.setText("⚙");
        aj.setTextColor(TOPO_FG);
        aj.setTextSize(15);
        aj.setPadding(0, 0, 0, 0);
        aj.setBackground(redondo(Color.TRANSPARENT, TOPO_FG, 1.5f));
        aj.setStateListAnimator(null);
        aj.setOnClickListener(v -> ajustes());
        if (tema == FUTURISTA) {
            View anel = new View(this);
            anel.setBackground(new AnelHud());
            marca.addView(anel, lp(dp(30), dp(30), 0, 0, 0, 8, 0));
        }
        marca.addView(aj, new LinearLayout.LayoutParams(dp(34), dp(34)));
        h.addView(marca);

        LinearLayout nums = linha(Gravity.CENTER);
        numFps = numero(nums, "Clipes hoje");
        numImagem = numero(nums, "Clipe atual");
        numCam = numero(nums, "Online");
        h.addView(nums, lp(-1, -2, 0, 0, 14, 0, 0));
        return h;
    }

    TextView numero(LinearLayout pai, String rot) {
        LinearLayout c = coluna();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView v = texto("—", tema == FUTURISTA ? 15 : 18, 700, tema == 0 ? Color.WHITE : ACENTO);
        numerico(v);
        c.addView(v);
        TextView r = rotulo(rot, 9, tema == 0 ? Color.WHITE : MUT, 0.1f);
        r.setTypeface(fonte(700));
        r.setAlpha(0.85f);
        c.addView(r, lp(-2, -2, 0, 0, 4, 0, 0));
        int folga = 0;
        if (tema != 0) {   // nos temas novos cada numero vira um cartao, como na previa
            c.setBackground(cartaoNumero());
            c.setPadding(dp(4), dp(9), dp(4), dp(9));
            folga = 3;
        }
        pai.addView(c, lp(0, -2, 1, folga, 0, folga, 0));
        return v;
    }

    LinearLayout bloco() {
        LinearLayout b = coluna();
        b.setBackground(caixa(PANEL, 18, LINHA, 1));
        b.setClipToOutline(true);
        return b;
    }

    // bloco do video: abas das cameras, palco 16:9 com chips e "Tela cheia", ferramentas
    LinearLayout montarBlocoVideo() {
        LinearLayout b = bloco();
        LinearLayout seg = linha(Gravity.CENTER);
        seg.setPadding(dp(16), dp(12), dp(16), 0);
        for (int i = 0; i < 2; i++) {
            final int k = i;
            botaoCam[i] = pilula(NOMES[i], true, v -> trocarCamera(k));
            seg.addView(botaoCam[i], lp(0, dp(38), 1, i == 0 ? 0 : 4, 0, i == 0 ? 4 : 0, 0));
        }
        abas = seg;
        b.addView(seg);

        LinearLayout corpo = coluna();
        corpo.setPadding(dp(16), dp(12), dp(16), dp(16));
        palco = new FrameLayout(this);
        palco.setBackground(caixa(Color.BLACK, 12, 0, 0));
        palco.setClipToOutline(true);
        tela = new TextureView(this);
        tela.setSurfaceTextureListener(this);
        palco.addView(tela, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout chips = linha(Gravity.CENTER_VERTICAL);
        chipVivo = chip(chips, "Conectando");
        pontoVivo = new View(this);
        pontoVivo.setBackground(redondo(MUT, 0, 0));
        ((LinearLayout) chipVivo.getParent()).addView(pontoVivo, 0, lp(dp(7), dp(7), 0, 0, 0, 6, 0));
        chipZoom = chip(chips, "1.0×");
        chipZoom.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        chipZoom.setLetterSpacing(0);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cp.setMargins(dp(12), dp(10), 0, 0);
        palco.addView(chips, cp);
        Button fs = new Button(this);
        fs.setText("⛶  Tela cheia");
        fs.setAllCaps(false);
        fs.setTypeface(fonte(600));
        fs.setTextSize(11);
        fs.setTextColor(Color.WHITE);
        fs.setBackground(redondo(alfa(0x99, BG), 0x59FFFFFF, 1));
        fs.setStateListAnimator(null);
        fs.setPadding(dp(12), 0, dp(12), 0);
        fs.setMinHeight(0);
        fs.setMinimumHeight(0);
        fs.setOnClickListener(v -> telaCheia(!cheia));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, dp(30), Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, dp(12), dp(10));
        palco.addView(fs, fp);
        flutuante = montarPadFlutuante();
        flutuante.setVisibility(View.GONE);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, dp(20), dp(52));
        palco.addView(flutuante, flp);
        gestos();
        corpo.addView(palco, new LinearLayout.LayoutParams(-1, dp(180)));

        LinearLayout tools = linha(Gravity.CENTER_VERTICAL);
        tools.addView(pilula("↻ Recarregar", false, v -> { reiniciar(); conectarControle(); }), lp(0, dp(36), 1.3f, 0, 0, 4, 0));
        botaoImagem = pilula("", false, v -> trocarImagem());
        tools.addView(botaoImagem, lp(0, dp(36), 1.1f, 4, 0, 4, 0));
        tools.addView(pilula("1×", false, v -> { zoom = 1; panX = panY = 0; aplicarMatriz(); }), lp(dp(44), dp(36), 0, 4, 0, 0, 0));
        ferramentas = tools;
        corpo.addView(tools, lp(-1, -2, 0, 0, 12, 0, 0));
        TextView dica = texto("Zoom digital: pinça no vídeo amplia, arraste para mover, toque duas vezes volta a 1×.", 12, 300, MUT);
        dica.setLineSpacing(0, 1.3f);
        corpo.addView(dica, lp(-1, -2, 0, 0, 8, 0, 0));
        b.addView(corpo);
        return b;
    }

    TextView chip(LinearLayout pai, String s) {
        LinearLayout c = linha(Gravity.CENTER_VERTICAL);
        c.setBackground(redondo(alfa(0xB3, BG), 0, 0));
        c.setPadding(dp(10), dp(4), dp(10), dp(4));
        TextView t = rotulo(s, 10, FG, 0.1f);
        c.addView(t);
        pai.addView(c, lp(-2, -2, 0, 0, 0, 6, 0));
        return t;
    }

    // bloco "MOVER CAMERA": pad em cruz 52dp com "+" magenta no meio, "- Zoom" e presets
    LinearLayout montarBlocoPtz() {
        LinearLayout b = bloco();
        LinearLayout bar = linha(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(10));
        LinearLayout tit = coluna();
        tit.addView(rotulo("Mover câmera", 13, FG, 0.06f));
        TextView sub = texto("Segure o botão; solte para parar", 11, 300, INFO);
        tit.addView(sub, lp(-2, -2, 0, 0, 3, 0, 0));
        bar.addView(tit, lp(0, -2, 1, 0, 0, 0, 0));
        ImageView pino = new ImageView(this);   // botao do mapa, como o .pinbtn da pagina
        pino.setImageResource(R.drawable.pino);
        if (tema != 0) pino.setColorFilter(ACENTO);   // a imagem e azul, boa so no Neon
        pino.setPadding(dp(6), dp(6), dp(6), dp(6));
        pino.setBackground(redondo(PANEL2, LINHA, 1.5f));
        pino.setContentDescription("Mapa da casa");
        pino.setOnClickListener(v -> abrirMapa());
        bar.addView(pino, new LinearLayout.LayoutParams(dp(34), dp(34)));
        b.addView(bar);
        LinearLayout corpo = coluna();
        corpo.setPadding(dp(16), dp(4), dp(16), dp(16));
        LinearLayout pad = cruz(52, 8, 14, 16);
        corpo.addView(pad, lp(-2, -2, 0, 0, 6, 0, 4));
        ((LinearLayout.LayoutParams) pad.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
        LinearLayout z = linha(Gravity.CENTER);
        z.addView(botaoPtz(pilula("－ Zoom", false, null), "ZoomWide", false), lp(0, dp(40), 1, 0, 0, 0, 0));
        corpo.addView(z, lp(-1, -2, 0, 0, 12, 0, 0));
        presets = linha(Gravity.CENTER);
        corpo.addView(presets, lp(-1, -2, 0, 0, 8, 0, 0));
        ptzMsg = texto("Pronto.", 11, 300, MUT);
        ptzMsg.setTypeface(Typeface.MONOSPACE);
        ptzMsg.setGravity(Gravity.CENTER);
        corpo.addView(ptzMsg, lp(-1, -2, 0, 0, 10, 0, 0));
        b.addView(corpo);
        return b;
    }

    void montarPresets() {
        presets.removeAllViews();
        for (int n = 1; n <= 3; n++) {
            final int p = n;
            String nome = PRESETS[cam()][n - 1];
            presets.addView(pilula(nome.isEmpty() ? String.valueOf(n) : n + " " + nome, false, v -> preset(p)),
                lp(0, dp(40), 1, n == 1 ? 0 : 4, 0, n == 3 ? 0 : 4, 0));
        }
    }

    // cruz de setas; lado e vao em dp; raio dos cantos; tamanho do texto
    LinearLayout cruz(int lado, int vao, int raio, int sp) {
        LinearLayout g = coluna();
        String[][] cmd = { { null, "DirectionUp", null }, { "DirectionLeft", "ZoomTile", "DirectionRight" }, { null, "DirectionDown", null } };
        String[][] sim = { { null, "▲", null }, { "◀", "＋", "▶" }, { null, "▼", null } };
        for (int r = 0; r < 3; r++) {
            LinearLayout l = new LinearLayout(this);
            for (int c = 0; c < 3; c++) {
                LinearLayout.LayoutParams p = lp(dp(lado), dp(lado), 0, c == 0 ? 0 : vao / 2, r == 0 ? 0 : vao / 2, c == 2 ? 0 : vao / 2, r == 2 ? 0 : vao / 2);
                if (cmd[r][c] == null) { l.addView(new View(this), p); continue; }
                boolean meio = r == 1 && c == 1;
                Button b = new Button(this);
                b.setText(sim[r][c]);
                b.setTextSize(meio ? sp + 4 : sp);
                b.setTextColor(meio ? BTN_FG : FG);
                b.setPadding(0, 0, 0, 0);
                b.setStateListAnimator(null);
                b.setTag(meio ? centroPtz() : caixa(PANEL2, raio, LINHA, 1));
                b.setBackground((Drawable) b.getTag());
                l.addView(botaoPtz(b, cmd[r][c], true), p);
            }
            g.addView(l);
        }
        return g;
    }

    // segurar = gira; soltar = para (igual aos botoes data-ptz da pagina)
    Button botaoPtz(Button b, String cmd, boolean encolhe) {
        final android.graphics.drawable.Drawable normal = b.getBackground();
        final boolean[] girando = { false };
        final Runnable comecar = () -> { girando[0] = true; ptz(cmd, true); };
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.setBackground(cmd.equals("ZoomTile") ? redondo(G2, G2, 1) : caixa(G2, 14, G2, 1));
                if (encolhe) { v.setScaleX(0.94f); v.setScaleY(0.94f); }
                girando[0] = false;
                ui.postDelayed(comecar, 150);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.setBackground(normal);
                v.setScaleX(1); v.setScaleY(1);
                ui.removeCallbacks(comecar);
                if (girando[0]) ptz(cmd, false);
                else if (a == MotionEvent.ACTION_UP) toqueCurto(cmd);
            }
            return true;
        });
        return b;
    }

    // tela cheia: pad flutuante igual ao .fpad da pagina
    FrameLayout montarPadFlutuante() {
        FrameLayout f = new FrameLayout(this);
        LinearLayout c = coluna();
        c.setBackground(caixa(alfa(0xD9, DIALOGO), 18, LINHA, 1));
        c.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView g = rotulo("Mover", 9, MUT, 0.1f);
        g.setPadding(dp(2), 0, 0, dp(6));
        c.addView(g);
        c.addView(cruz(38, 4, 10, 12));
        LinearLayout mini = linha(Gravity.CENTER);
        mini.addView(botaoPtz(pilula("－", true, null), "ZoomWide", false), lp(0, dp(28), 1, 0, 6, 2, 0));
        mini.addView(botaoPtz(pilula("＋", true, null), "ZoomTile", false), lp(0, dp(28), 1, 2, 6, 0, 0));
        c.addView(mini);
        f.addView(c);
        return f;
    }

    void ajustarAlturaVideo() {
        int w = palco.getWidth();
        if (w == 0 || cheia) return;
        palco.setLayoutParams(new LinearLayout.LayoutParams(-1, w * 9 / 16));
    }

    void atualizarBotoes() {
        for (int i = 0; i < 2; i++) {
            boolean sel = i == cam();
            marcaBtn(botaoCam[i], sel);
        }
        botaoImagem.setText(stream() == 1 ? "Imagem: leve" : "Imagem: HD");
        montarPresets();
    }

    static final int CONECTANDO = 0, AO_VIVO = 1, FALHA = 2;
    // como na pagina: ponto do chip vermelho quando ao vivo; bolinha do logo verde (ok) ou vermelha (falha)
    void vivo(String s, int estado) {
        ui.post(() -> {
            chipVivo.setText(s.toUpperCase(Locale.ROOT));
            chipVivo.setTextColor(estado == AO_VIVO ? FG : MUT);
            ((GradientDrawable) pontoVivo.getBackground()).setColor(estado == AO_VIVO ? RUIM : MUT);
            ((GradientDrawable) bolinha.getBackground()).setColor(estado == AO_VIVO ? OK : estado == FALHA ? RUIM : 0xFF777777);
        });
    }

    void avisar(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
    void msgPtz(String s) { ui.post(() -> ptzMsg.setText(s)); }

    // ---------- temas: guardados so neste celular ----------
    void temas() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout l = coluna();
        l.setBackground(caixa(DIALOGO, 18, LINHA, 1));
        l.setPadding(dp(18), dp(16), dp(18), dp(16));
        l.addView(rotulo("Temas", 13, FG, 0.06f));
        l.addView(texto("Toque em um tema para usar.", 11, 300, INFO), lp(-2, -2, 0, 0, 3, 0, 6));
        for (int i = 0; i < TEMAS.length; i++) {
            final int t = i;
            int[] c = CORES[i];   // cada cartao mostra as cores do proprio tema
            LinearLayout cartao = linha(Gravity.CENTER_VERTICAL);
            cartao.setBackground(caixa(c[0], 12, i == tema ? c[11] : c[3], i == tema ? 2 : 1));
            cartao.setPadding(dp(12), dp(12), dp(12), dp(12));
            View amostra = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { c[15], c[16], c[17] });
            g.setCornerRadius(dp(cantos(10)));
            amostra.setBackground(g);
            cartao.addView(amostra, lp(dp(44), dp(44), 0, 0, 0, 12, 0));
            cartao.addView(texto(TEMAS[i], 14, 600, c[8]), lp(0, -2, 1, 0, 0, 8, 0));
            cartao.addView(texto(i == tema ? "Em uso" : "Usar", 12, 500, c[11]));
            cartao.setContentDescription("Tema " + TEMAS[i] + (i == tema ? ", em uso" : ""));
            cartao.setOnClickListener(v -> {
                d.dismiss();
                if (t == tema) return;
                prefs.edit().putInt("tema", t).apply();
                aplicarTema(t);
                recreate();   // as cores sao lidas ao montar as telas
            });
            l.addView(cartao, lp(-1, -2, 0, 0, 10, 0, 0));
        }
        l.addView(pilula("Fechar", false, v -> d.dismiss()), lp(-1, dp(38), 0, 0, 16, 0, 0));
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        d.setContentView(sv);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.show();
    }

    // ---------- ajustes (ficam so neste celular), no mesmo estilo ----------
    void ajustes() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout l = coluna();
        l.setBackground(caixa(DIALOGO, 18, LINHA, 1));
        l.setPadding(dp(18), dp(16), dp(18), dp(16));
        l.addView(rotulo("Câmeras", 13, FG, 0.06f));
        l.addView(texto("Login e senha ficam só neste celular.", 11, 300, INFO), lp(-2, -2, 0, 0, 3, 0, 6));
        EditText[][] campos = new EditText[2][3];
        final boolean[][] inv = { { invLR(0), invUD(0) }, { invLR(1), invUD(1) } };
        for (int i = 0; i < 2; i++) {
            l.addView(rotulo(NOMES[i], 10, MUT, 0.1f), lp(-2, -2, 0, 0, 12, 0, 2));
            campos[i][0] = campo(l, "IP", host(i), false);
            campos[i][1] = campo(l, "Usuário", user(i), false);
            campos[i][2] = campo(l, "Senha", pass(i), true);
            LinearLayout chaves = linha(Gravity.CENTER);
            String[] txt = { "Inverter ◀ ▶", "Inverter ▲ ▼" };
            for (int k = 0; k < 2; k++) {
                final int ci = i, ck = k;
                Button t = pilula(txt[k], true, null);
                t.setTextSize(12);
                Runnable pintar = () -> marcaBtn(t, inv[ci][ck]);
                t.setOnClickListener(v -> { inv[ci][ck] = !inv[ci][ck]; pintar.run(); });
                pintar.run();
                chaves.addView(t, lp(0, dp(34), 1, k == 0 ? 0 : 4, 6, k == 0 ? 4 : 0, 0));
            }
            l.addView(chaves);
        }
        l.addView(rotulo("Imagem", 10, MUT, 0.1f), lp(-2, -2, 0, 0, 12, 0, 4));
        final int[] escolha = { stream() };
        LinearLayout im = linha(Gravity.CENTER);
        Button leve = pilula("Leve (800×448)", true, null), hd = pilula("HD (2304×1296)", true, null);
        Runnable marcar = () -> {
            marcaBtn(leve, escolha[0] == 1);
            marcaBtn(hd, escolha[0] == 0);
        };
        leve.setOnClickListener(v -> { escolha[0] = 1; marcar.run(); });
        hd.setOnClickListener(v -> { escolha[0] = 0; marcar.run(); });
        marcar.run();
        im.addView(leve, lp(0, dp(38), 1, 0, 0, 4, 0));
        im.addView(hd, lp(0, dp(38), 1, 4, 0, 0, 0));
        l.addView(im);
        // Moto G: servidor de status so de leitura (gravacao e ultimos clipes); usuario e senha sao os do status/httpd.conf do celular
        l.addView(rotulo("Moto G (gravação)", 10, MUT, 0.1f), lp(-2, -2, 0, 0, 12, 0, 2));
        EditText stHost = campo(l, "IP do Moto G", stHost(), false);
        EditText stUser = campo(l, "Usuário", prefs.getString("stUser", "cambox"), false);
        EditText stPass = campo(l, "Senha do status", prefs.getString("stPass", ""), true);
        l.addView(pilula("Temas", false, v -> { d.dismiss(); temas(); }), lp(-1, dp(38), 0, 0, 16, 0, 0));
        l.addView(pilula("Sobre o CAM house", false, v -> { d.dismiss(); sobre(); }), lp(-1, dp(38), 0, 0, 8, 0, 0));
        LinearLayout acoes = linha(Gravity.END);
        acoes.addView(pilula("Cancelar", false, v -> d.dismiss()), lp(dp(110), dp(40), 0, 0, 16, 8, 0));
        Button salvar = pilula("Salvar", true, v -> {
            SharedPreferences.Editor e = prefs.edit();
            for (int i = 0; i < 2; i++)
                e.putString("host" + i, campos[i][0].getText().toString().trim())
                    .putString("user" + i, campos[i][1].getText().toString().trim())
                    .putString("pass" + i, campos[i][2].getText().toString())
                    .putBoolean("invLR" + i, inv[i][0]).putBoolean("invUD" + i, inv[i][1]);
            e.putInt("stream", escolha[0]);
            e.putString("stHost", stHost.getText().toString().trim()).putString("stUser", stUser.getText().toString().trim())
                .putString("stPass", stPass.getText().toString()).apply();
            d.dismiss();
            atualizarBotoes();
            reiniciar();
            conectarControle();
            carregarGravacao();
        });
        salvar.setBackground(degrade(999));
        salvar.setTextColor(BTN_FG);
        acoes.addView(salvar, lp(dp(110), dp(40), 0, 0, 16, 0, 0));
        l.addView(acoes);
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        d.setContentView(sv);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9), -2);
        d.show();
    }

    EditText campo(LinearLayout l, String dica, String valor, boolean senha) {
        EditText e = new EditText(this);
        e.setHint(dica);
        e.setHintTextColor(MUT);
        e.setText(valor);
        e.setTextColor(FG);
        e.setTextSize(13);
        e.setTypeface(fonte(300));
        e.setSingleLine();
        e.setBackground(caixa(BG, 10, LINHA, 1));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setInputType(senha ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        l.addView(e, lp(-1, -2, 0, 0, 4, 0, 0));
        return e;
    }

    void trocarCamera(int i) {
        if (i == cam()) return;
        prefs.edit().putInt("cam", i).apply();
        atualizarBotoes();
        zoom = 1; panX = panY = 0;
        if (user(i).isEmpty()) { ajustes(); return; }
        reiniciar();
        conectarControle();
        if (ajCorpo.getVisibility() == View.VISIBLE) carregarAjustes();
        desenharGravacao();
    }

    void trocarImagem() {
        prefs.edit().putInt("stream", stream() == 1 ? 0 : 1).apply();
        atualizarBotoes();
        reiniciar();
    }

    // ---------- controle (DVRIP) ----------
    void conectarControle() {
        final int c = cam();
        rede.removeCallbacksAndMessages(null);
        rede.post(() -> {
            try { dvrip.conectar(host(c), user(c), pass(c)); msgPtz("Pronto."); }
            catch (Exception e) { msgPtz("Controle: " + e.getMessage()); }
        });
        rede.postDelayed(this::manter, 20000);
    }

    void manter() {   // keepalive: sem ele a camera derruba o controle
        try { if (dvrip.conectado()) dvrip.keepalive(); } catch (Exception e) { }
        rede.postDelayed(this::manter, 20000);
    }

    void garantirControle() throws Exception {
        if (!dvrip.conectado()) dvrip.conectar(host(cam()), user(cam()), pass(cam()));
    }

    static final int PASSO = 3;   // velocidade do PTZ (a camera aceita 1..8; o PC usa 5)

    // a imagem da camera e espelhada (PictureMirror): sem inverter, a seta da direita gira para a esquerda (igual no iCSee)
    boolean invLR(int i) { return prefs.getBoolean("invLR" + i, true); }
    boolean invUD(int i) { return prefs.getBoolean("invUD" + i, false); }
    String direcao(String cmd) {
        int c = cam();
        if (invLR(c) && cmd.equals("DirectionLeft")) return "DirectionRight";
        if (invLR(c) && cmd.equals("DirectionRight")) return "DirectionLeft";
        if (invUD(c) && cmd.equals("DirectionUp")) return "DirectionDown";
        if (invUD(c) && cmd.equals("DirectionDown")) return "DirectionUp";
        return cmd;
    }

    // toque rapido = so um passo curto; segurando mais de 150 ms = gira ate soltar
    void toqueCurto(String cmd) {
        rede.post(() -> {
            try {
                garantirControle();
                String d = direcao(cmd);
                dvrip.ptz(d, true, PASSO);
                Thread.sleep(150);
                dvrip.ptz(d, false, PASSO);
                msgPtz("Pronto.");
            } catch (Exception e) { msgPtz("Não consegui mover: " + e.getMessage()); }
        });
    }

    void ptz(String cmd, boolean comecar) {
        rede.post(() -> {
            try { garantirControle(); dvrip.ptz(direcao(cmd), comecar, PASSO); if (comecar) msgPtz("Movendo..."); else msgPtz("Pronto."); }
            catch (Exception e) { if (comecar) msgPtz("Não consegui mover: " + e.getMessage()); }
        });
    }

    void preset(int n) {
        rede.post(() -> {
            try { garantirControle(); dvrip.irPreset(n); msgPtz("Indo para o preset " + n + "..."); }
            catch (Exception e) { msgPtz("Preset: " + e.getMessage()); }
        });
    }

    // ---------- ajustes da camera: mesmos campos e escalas calibradas do server.js (SETTINGS / SET_UI) ----------
    static class Aj {
        final String chave, rotulo, bloco, tipo;   // tipo: sel, chk, faixa, ro
        final Object[] caminho;
        Object[][] opcoes;
        String bool;   // chk: "int" (0/1), "bool" (true/false), "hex" ("0x00000001")
        Object[] tambem;   // outro campo gravado junto (volume esquerdo e direito)
        Aj(String chave, String rotulo, String bloco, String tipo, Object... caminho) {
            this.chave = chave; this.rotulo = rotulo; this.bloco = bloco; this.tipo = tipo; this.caminho = caminho;
        }
        Aj op(Object[][] o) { opcoes = o; return this; }
        Aj b(String t) { bool = t; return this; }
        Aj e(Object... c) { tambem = c; return this; }
    }

    static Object[][] segundos(int... s) {
        Object[][] o = new Object[s.length][];
        for (int i = 0; i < s.length; i++) o[i] = new Object[] { s[i], s[i] + " s" };
        return o;
    }

    static final Aj[] AJUSTES = {
        new Aj("luzModo", "Luz noturna", "Camera.WhiteLight", "sel", "WorkMode")
            .op(new Object[][] { { "Auto", "Full Night Vision" }, { "Close", "Visão noturna infravermelha" }, { "Intelligent", "Alerta de luz dupla" } }),
        new Aj("luzSensib", "Sensibilidade da luz", "Camera.WhiteLight", "sel", "MoveTrigLight", "Level")
            .op(new Object[][] { { 1, "Baixo" }, { 3, "Médio" }, { 5, "Alto" } }),
        new Aj("luzDuracao", "Duração da luz", "Camera.WhiteLight", "sel", "MoveTrigLight", "Duration").op(segundos(5, 10, 15, 30, 60)),
        new Aj("luzBrilho", "Brilho da luz", "Camera.WhiteLight", "ro", "Brightness"),   // escala nao confirmada: so leitura
        new Aj("rastreio", "Rastreamento de movimento", "Detect.DetectTrack", "chk", "Enable").b("int"),
        new Aj("rastrSensib", "Sensibilidade do rastreamento", "Detect.DetectTrack", "sel", "Sensitivity")
            .op(new Object[][] { { 0, "Baixo" }, { 1, "Médio" }, { 2, "Alto" } }),
        new Aj("rastrRetorno", "Tempo de retorno", "Detect.DetectTrack", "sel", "ReturnTime").op(segundos(3, 5, 10, 15, 30)),
        new Aj("movimento", "Detecção de movimento (alarme)", "Detect.MotionDetect", "chk", 0, "Enable").b("bool"),
        new Aj("movNivel", "Sensibilidade do alarme", "Detect.MotionDetect", "sel", 0, "Level")
            .op(new Object[][] { { 1, "Baixo" }, { 3, "Médio" }, { 6, "Alto" } }),
        new Aj("humano", "Detecção de corpo humano", "Detect.HumanDetection", "chk", 0, "Enable").b("bool"),
        new Aj("girarVert", "Girar imagem cima-baixo", "Camera.Param", "chk", 0, "PictureFlip").b("hex"),
        new Aj("girarHoriz", "Girar imagem esquerda-direita", "Camera.Param", "chk", 0, "PictureMirror").b("hex"),
        new Aj("volume", "Volume do alto-falante", "fVideo.Volume", "faixa", 0, "LeftVolume").e(0, "RightVolume"),
    };

    LinearLayout ajCorpo, ajLista;
    TextView ajMsg, cfgSaida;
    final java.util.Set<String> blocosSalvos = new java.util.HashSet<>();

    LinearLayout montarBlocoAjustes() {
        LinearLayout b = bloco();
        LinearLayout bar = linha(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(10));
        LinearLayout tit = coluna();
        tit.addView(rotulo("Ajustes da câmera", 13, FG, 0.06f));
        ajMsg = texto("Toque para abrir", 11, 300, INFO);
        tit.addView(ajMsg, lp(-2, -2, 0, 0, 3, 0, 0));
        bar.addView(tit, lp(0, -2, 1, 0, 0, 0, 0));
        Button eng = new Button(this);
        eng.setText("⚙");
        eng.setTextColor(FG);
        eng.setTextSize(15);
        eng.setPadding(0, 0, 0, 0);
        eng.setBackground(caixa(Color.TRANSPARENT, 999, FG, 1.5f));
        eng.setStateListAnimator(null);
        bar.addView(eng, new LinearLayout.LayoutParams(dp(30), dp(30)));
        b.addView(bar);
        ajCorpo = coluna();
        ajCorpo.setPadding(dp(16), 0, dp(16), dp(16));
        ajCorpo.setVisibility(View.GONE);
        ajLista = coluna();
        ajCorpo.addView(ajLista);
        // config bruta, so leitura (igual a caixa "Config" da pagina)
        ajCorpo.addView(rotulo("Config bruta (só leitura)", 10, MUT, 0.1f), lp(-2, -2, 0, 0, 16, 0, 6));
        LinearLayout cfg = linha(Gravity.CENTER_VERTICAL);
        EditText nome = new EditText(this);
        nome.setText("Camera.Param");
        nome.setTextColor(FG);
        nome.setTextSize(13);
        nome.setTypeface(fonte(300));
        nome.setSingleLine();
        nome.setBackground(caixa(BG, 10, LINHA, 1));
        nome.setPadding(dp(10), dp(7), dp(10), dp(7));
        cfg.addView(nome, lp(0, -2, 1, 0, 0, 8, 0));
        cfg.addView(pilula("Ler", false, v -> lerBruta(nome.getText().toString().trim())), lp(dp(64), dp(36), 0, 0, 0, 0, 0));
        ajCorpo.addView(cfg);
        cfgSaida = texto("—", 12, 300, MUT);
        cfgSaida.setTypeface(Typeface.MONOSPACE);
        cfgSaida.setBackground(caixa(BG, 10, LINHA_SUAVE, 1));
        cfgSaida.setPadding(dp(10), dp(10), dp(10), dp(10));
        cfgSaida.setTextIsSelectable(true);
        ajCorpo.addView(cfgSaida, lp(-1, -2, 0, 0, 10, 0, 0));
        b.addView(ajCorpo);
        View.OnClickListener abrir = v -> {
            boolean ab = ajCorpo.getVisibility() != View.VISIBLE;
            ajCorpo.setVisibility(ab ? View.VISIBLE : View.GONE);
            if (ab) carregarAjustes(); else ajMsg.setText("Toque para abrir");
        };
        bar.setOnClickListener(abrir);
        eng.setOnClickListener(abrir);
        return b;
    }

    static Object pegar(Object o, Object[] cam, int ate) {
        for (int i = 0; i < ate && o != null; i++) {
            if (cam[i] instanceof Integer) o = o instanceof org.json.JSONArray ? ((org.json.JSONArray) o).opt((Integer) cam[i]) : null;
            else o = o instanceof org.json.JSONObject ? ((org.json.JSONObject) o).opt((String) cam[i]) : null;
        }
        return o;
    }

    static void por(Object o, Object[] cam, Object v) throws org.json.JSONException {
        Object pai = pegar(o, cam, cam.length - 1);
        Object ult = cam[cam.length - 1];
        if (ult instanceof Integer) ((org.json.JSONArray) pai).put((Integer) ult, v);
        else ((org.json.JSONObject) pai).put((String) ult, v);
    }

    static boolean ligado(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        if (v instanceof String) { try { return Long.decode((String) v) == 1; } catch (Exception e) { return false; } }
        return false;
    }

    Object lerBloco(String bloco) throws Exception {
        String r = dvrip.lerConfig(bloco);
        org.json.JSONObject j = new org.json.JSONObject(r);
        if (j.optInt("Ret") != 100 || !j.has(bloco)) throw new Exception(bloco + ": Ret " + j.optInt("Ret"));
        return j.get(bloco);
    }

    void carregarAjustes() {
        final int c = cam();
        ajMsg.setText("Lendo da câmera…");
        rede.post(() -> {
            Map<String, Object> blocos = new HashMap<>();
            try {
                garantirControle();
                for (Aj a : AJUSTES) if (!blocos.containsKey(a.bloco)) { try { blocos.put(a.bloco, lerBloco(a.bloco)); } catch (Exception e) { blocos.put(a.bloco, null); } }
            } catch (Exception e) { ui.post(() -> ajMsg.setText("Erro: " + e.getMessage())); return; }
            ui.post(() -> { if (c == cam()) desenharAjustes(blocos); });
        });
    }

    void desenharAjustes(Map<String, Object> blocos) {
        ajLista.removeAllViews();
        for (Aj a : AJUSTES) {
            Object v = pegar(blocos.get(a.bloco), a.caminho, a.caminho.length);
            if (v == null) continue;   // esta camera nao tem esse campo
            LinearLayout l = linha(Gravity.CENTER_VERTICAL);
            l.setPadding(0, dp(9), 0, dp(9));
            l.addView(texto(a.rotulo, 13, 300, FG), lp(0, -2, 1, 0, 0, 10, 0));
            if (a.tipo.equals("sel")) {
                Button bt = pilula(nomeOpcao(a, v), false, null);
                bt.setTextSize(12);
                bt.setOnClickListener(x -> {
                    String[] nomes = new String[a.opcoes.length];
                    for (int i = 0; i < nomes.length; i++) nomes[i] = (String) a.opcoes[i][1];
                    new android.app.AlertDialog.Builder(this).setTitle(a.rotulo)
                        .setItems(nomes, (d, i) -> { bt.setText(nomes[i] + "  ▾"); gravarAjuste(a, a.opcoes[i][0]); }).show();
                });
                l.addView(bt, lp(-2, dp(34), 0, 0, 0, 0, 0));
            } else if (a.tipo.equals("chk")) {
                final boolean[] on = { ligado(v) };
                Button bt = pilula(on[0] ? "Ligado" : "Desligado", true, null);
                bt.setTextSize(12);
                Runnable pinta = () -> { bt.setText(on[0] ? "Ligado" : "Desligado"); marcaBtn(bt, on[0]); };
                pinta.run();
                bt.setOnClickListener(x -> {
                    on[0] = !on[0];
                    pinta.run();
                    gravarAjuste(a, a.bool.equals("int") ? (Object) (on[0] ? 1 : 0) : a.bool.equals("hex") ? (on[0] ? "0x00000001" : "0x00000000") : (Object) on[0]);
                });
                l.addView(bt, lp(dp(104), dp(34), 0, 0, 0, 0, 0));
            } else if (a.tipo.equals("faixa")) {
                TextView n = texto(String.valueOf(v), 12, 500, MUT);
                n.setTypeface(Typeface.MONOSPACE);
                android.widget.SeekBar s = new android.widget.SeekBar(this);
                s.setMax(99);   // 1..100
                s.setProgress(Math.max(0, ((Number) v).intValue() - 1));
                s.setProgressTintList(android.content.res.ColorStateList.valueOf(G2));
                s.setThumbTintList(android.content.res.ColorStateList.valueOf(G2));
                s.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) { n.setText(String.valueOf(p + 1)); }
                    public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                    public void onStopTrackingTouch(android.widget.SeekBar sb) { gravarAjuste(a, sb.getProgress() + 1); }
                });
                l.addView(s, lp(dp(130), -2, 0, 0, 0, 4, 0));
                l.addView(n, lp(dp(32), -2, 0, 0, 0, 0, 0));
            } else {
                TextView ro = texto(String.valueOf(v), 12, 300, MUT);
                ro.setTypeface(Typeface.MONOSPACE);
                l.addView(ro);
            }
            ajLista.addView(l);
            View sep = new View(this);
            sep.setBackgroundColor(LINHA_SUAVE);
            ajLista.addView(sep, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        ajMsg.setText(ajLista.getChildCount() == 0 ? "A câmera não respondeu os ajustes" : "Cada mudança grava na câmera na hora.");
    }

    String nomeOpcao(Aj a, Object v) {
        for (Object[] o : a.opcoes) if (String.valueOf(o[0]).equals(String.valueOf(v))) return o[1] + "  ▾";
        return "outro (" + v + ")  ▾";
    }

    // le o bloco de novo, guarda copia (1a vez nesta abertura do app), muda so o campo e grava; depois confere
    void gravarAjuste(Aj a, Object valor) {
        final int c = cam();
        ajMsg.setText("Gravando…");
        rede.post(() -> {
            try {
                garantirControle();
                Object blk = lerBloco(a.bloco);
                if (blocosSalvos.add(c + a.bloco)) {
                    java.io.File dir = new java.io.File(getFilesDir(), "backup-camera");
                    dir.mkdirs();
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "cam" + c + "_" + a.bloco + "_" + ts + ".json"))) { w.write(blk.toString()); }
                }
                por(blk, a.caminho, valor);
                if (a.tambem != null) por(blk, a.tambem, valor);
                int r = dvrip.gravarConfig(a.bloco, blk.toString());
                Object agora = pegar(lerBloco(a.bloco), a.caminho, a.caminho.length);
                boolean ok = String.valueOf(agora).equals(String.valueOf(valor));
                ui.post(() -> ajMsg.setText(ok ? "Gravado na câmera ✓" : "A câmera respondeu Ret " + r + " e ficou com " + agora));
            } catch (Exception e) { ui.post(() -> ajMsg.setText("Não gravou: " + e.getMessage())); }
        });
    }

    void lerBruta(String nome) {
        if (nome.isEmpty()) return;
        cfgSaida.setText("lendo…");
        rede.post(() -> {
            try {
                garantirControle();
                Object o = lerBloco(nome);
                String t = o instanceof org.json.JSONObject ? ((org.json.JSONObject) o).toString(2) : ((org.json.JSONArray) o).toString(2);
                ui.post(() -> cfgSaida.setText(t));
            } catch (Exception e) { ui.post(() -> cfgSaida.setText("erro: " + e.getMessage())); }
        });
    }

    // ---------- mapa da casa: assets/mapa.html (Leaflet + satelite Esri), ponto salvo so neste celular ----------
    void abrirMapa() {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.webkit.WebView w = new android.webkit.WebView(this);
        w.setBackgroundColor(DIALOGO);
        w.getSettings().setJavaScriptEnabled(true);
        w.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView v, android.webkit.WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"camhouse".equals(u.getScheme())) return false;   // mapa so fala com o app por camhouse://
                if ("salvar".equals(u.getHost())) {
                    try {
                        double lat = Double.parseDouble(u.getQueryParameter("lat")), lon = Double.parseDouble(u.getQueryParameter("lon"));
                        if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                            prefs.edit().putString("casaLat", String.valueOf(lat)).putString("casaLon", String.valueOf(lon)).apply();
                            avisar("Local da casa salvo neste celular");
                        }
                    } catch (Exception e) { avisar("Local inválido"); }
                } else if ("fechar".equals(u.getHost())) d.dismiss();
                return true;
            }
        });
        String lat = prefs.getString("casaLat", null), lon = prefs.getString("casaLon", null);
        if (lat == null) {   // padrao opcional: assets/casa.json {"lat":..,"lon":..} (nao vem no repositorio)
            try (java.io.InputStream in = getAssets().open("casa.json")) {
                ByteArrayOutputStream bs = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                for (int n; (n = in.read(buf)) > 0; ) bs.write(buf, 0, n);
                org.json.JSONObject j = new org.json.JSONObject(bs.toString("UTF-8"));
                lat = String.valueOf(j.getDouble("lat"));
                lon = String.valueOf(j.getDouble("lon"));
            } catch (Exception e) { }
        }
        w.loadUrl("file:///android_asset/mapa.html" + (lat != null ? "#lat=" + lat + "&lon=" + lon : ""));
        d.setContentView(w);
        d.setOnDismissListener(x -> w.destroy());
        d.show();
    }

    // ---------- som: bloco magenta igual ao "Gravacao" da pagina (Tirar foto, Ouvir, Falar) ----------
    View checkOuvir, checkFalar, checkSons;
    TextView rotOuvir, rotFalar, rotSons, msgSom;
    volatile android.media.AudioTrack faixa;
    volatile boolean falando;
    Thread falador;

    LinearLayout montarBlocoSom() {
        LinearLayout b = coluna();
        b.setBackground(degrade(18));
        LinearLayout bar = coluna();
        bar.setPadding(dp(16), dp(14), dp(16), dp(10));
        bar.addView(rotulo("Gravação", 13, TOPO_FG, 0.06f));
        gravSub = texto("Gravação no Moto G", 11, 300, TOPO_FG);
        gravSub.setAlpha(0.85f);
        bar.addView(gravSub, lp(-2, -2, 0, 0, 3, 0, 0));
        b.addView(bar);
        LinearLayout corpo = coluna();
        corpo.setPadding(dp(16), dp(4), dp(16), dp(16));
        Button cta = new Button(this);   // .cta da pagina
        cta.setText("TIRAR FOTO");
        cta.setTypeface(fonte(700));
        cta.setLetterSpacing(0.08f);
        cta.setTextSize(14);
        cta.setTextColor(tema == 0 ? Color.WHITE : BTN_FG);
        cta.setBackground(tema == 0 ? caixa(BG, 999, Color.WHITE, 1.5f) : degrade(999));
        cta.setStateListAnimator(null);
        cta.setOnClickListener(v -> foto());
        corpo.addView(cta, lp(-1, dp(48), 0, 0, 0, 0, 12));
        LinearLayout tempos = linha(Gravity.CENTER);   // .times da pagina: proximo envio e quanto falta
        gravProx = tempo(tempos, "Próximo envio");
        gravFalta = tempo(tempos, "Falta");
        corpo.addView(tempos, lp(-1, -2, 0, 0, 0, 0, 8));
        gravMsg = texto("", 12, 300, TOPO_FG);
        gravMsg.setVisibility(View.GONE);
        corpo.addView(gravMsg, lp(-1, -2, 0, 0, 0, 0, 10));
        rotOuvir = new TextView(this);
        checkOuvir = acao(corpo, "🔊", rotOuvir, "Ouvir áudio ao vivo", v -> alternarOuvir());
        rotFalar = new TextView(this);
        checkFalar = acao(corpo, "🎤", rotFalar, "Falar pela câmera", v -> alternarFala());
        rotSons = new TextView(this);
        checkSons = acao(corpo, "🎵", rotSons, "Sons", v -> { if (tocandoSom) pararSom(); else menuSons(); });
        msgSom = texto("", 12, 300, TOPO_FG);
        msgSom.setVisibility(View.GONE);
        corpo.addView(msgSom, lp(-1, -2, 0, 0, 4, 0, 0));
        b.addView(corpo);
        return b;
    }

    // ---------- Sobre o CAM house (nota do desenvolvedor) ----------
    String versao() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    static final String[][] NOVIDADES = {
        { "1.0", "Versão oficial: o CAM house agora fala direto com as câmeras, sem depender do PC." },
        { "0.7", "Gravação e últimos clipes do Moto G no app; tela Sobre." },
        { "0.6", "Ajustes da câmera, config bruta e mapa da casa." },
        { "0.5", "Ouvir e falar pela câmera; Tirar foto." },
        { "0.4", "Setas mais suaves e esquerda/direita corrigidas." },
        { "0.3", "Visual e fontes iguais ao CAM house." },
        { "0.2", "Setas, zoom, presets, 2 câmeras, foto e tela cheia." },
        { "0.1", "Vídeo direto da câmera, sem PC, em casa e no 4G." },
    };

    void sobre() {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ScrollView sv = new ScrollView(this);
        sv.setBackground(fundoTela());
        LinearLayout l = coluna();
        l.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(l);
        // cabecalho igual ao cartao do topo
        LinearLayout h = linha(Gravity.CENTER_VERTICAL);
        h.setBackground(degrade(18));
        h.setPadding(dp(16), dp(14), dp(16), dp(14));
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.ic_camera_fg);
        img.setBackground(redondo(BG, TOPO_FG, 4));
        img.setPadding(dp(5), dp(5), dp(5), dp(5));
        h.addView(img, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout nome = coluna();
        ImageView wm = new ImageView(this);
        wm.setImageResource(R.drawable.wordmark);
        if (tema == FUTURISTA) wm.setColorFilter(ACENTO);
        wm.setAdjustViewBounds(true);
        wm.setScaleType(ImageView.ScaleType.FIT_START);
        nome.addView(wm, new LinearLayout.LayoutParams(-2, dp(40)));
        TextView v = rotulo("Versão " + versao() + " · " + getString(R.string.data_build), 9, TOPO_FG, 0.06f);
        v.setAlpha(0.9f);
        nome.addView(v, lp(-2, -2, 0, 0, 4, 0, 0));
        h.addView(nome, lp(0, -2, 1, 12, 0, 0, 0));
        l.addView(h);
        l.addView(secao("Nota do desenvolvedor",
            "O CAM house nasceu para cuidar da minha casa do meu jeito: ver e mexer nas câmeras de qualquer lugar, sem depender da nuvem do fabricante. "
            + "Tudo funciona direto entre o celular, as câmeras e a casa, com as gravações indo para o meu Telegram.\n\n"
            + "Feito por erikoliver01, 2026. Código aberto (licença MIT)."), lp(-1, -2, 0, 0, 16, 0, 0));
        l.addView(secao("Como funciona",
            "• Vídeo e controle direto da câmera, sem passar pelo PC.\n"
            + "• Fora de casa, pelo Tailscale, com a ponte no Moto G.\n"
            + "• Gravação 24 h no Moto G, com os clipes indo para o Telegram."), lp(-1, -2, 0, 0, 12, 0, 0));
        l.addView(secao("Privacidade",
            "Logins e senhas ficam só neste celular. Nada passa por servidores de terceiros, a não ser as imagens do mapa (Esri) e o Telegram."),
            lp(-1, -2, 0, 0, 12, 0, 0));
        StringBuilder nov = new StringBuilder();
        for (String[] n : NOVIDADES) nov.append("v").append(n[0]).append(" — ").append(n[1]).append("\n");
        l.addView(secao("O que há de novo", nov.toString().trim()), lp(-1, -2, 0, 0, 12, 0, 0));
        l.addView(secao("Créditos e licenças",
            "• Fonte Lexend — SIL Open Font License 1.1\n"
            + "• Mapa: Leaflet (BSD-2-Clause) · imagens de satélite © Esri\n"
            + "• Fonte Bebas Neue — SIL Open Font License 1.1\n"
            + "• Acesso fora de casa: Tailscale"), lp(-1, -2, 0, 0, 12, 0, 0));
        l.addView(pilula("Fechar", true, x -> d.dismiss()), lp(-1, dp(44), 0, 0, 20, 0, 0));
        d.setContentView(sv);
        d.show();
    }

    LinearLayout secao(String titulo, String corpo) {
        LinearLayout b = bloco();
        b.setPadding(dp(16), dp(14), dp(16), dp(16));
        b.addView(rotulo(titulo, 13, FG, 0.06f));
        TextView t = texto(corpo, 13, 300, FG);
        t.setLineSpacing(0, 1.4f);
        b.addView(t, lp(-1, -2, 0, 0, 8, 0, 0));
        return b;
    }

    // ---------- gravacao: status do Moto G (servidor so de leitura na porta 8023), igual ao painel da pagina ----------
    TextView gravSub, gravProx, gravFalta, gravMsg;
    LinearLayout blocoClipes, listaClipes;
    volatile Map<String, Object> grav;   // resultado de lerPainel
    long gravSkew;
    static final String[] IDS = { "porta", "camera2" };

    TextView tempo(LinearLayout pai, String rot) {
        LinearLayout c = coluna();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { alfa(0x38, TOPO_FG), alfa(0x0F, TOPO_FG) });
        g.setCornerRadius(dp(cantos(14)));
        c.setBackground(g);
        c.setPadding(dp(8), dp(12), dp(8), dp(12));
        TextView v = texto("--:--", tema == FUTURISTA ? 24 : 28, 700, TOPO_FG);
        numerico(v);
        c.addView(v);
        TextView r = rotulo(rot, 10, TOPO_FG, 0.08f);
        c.addView(r, lp(-2, -2, 0, 0, 2, 0, 0));
        pai.addView(c, lp(0, -2, 1, pai.getChildCount() == 0 ? 0 : 6, 0, pai.getChildCount() == 0 ? 6 : 0, 0));
        return v;
    }

    LinearLayout montarBlocoClipes() {
        LinearLayout b = bloco();
        LinearLayout bar = coluna();
        bar.setPadding(dp(16), dp(14), dp(16), dp(10));
        bar.addView(rotulo("Últimos clipes", 13, FG, 0.06f));
        bar.addView(texto("Enviados ao canal do Telegram pelo celular", 11, 300, INFO), lp(-2, -2, 0, 0, 3, 0, 0));
        b.addView(bar);
        listaClipes = coluna();
        listaClipes.setPadding(dp(16), 0, dp(16), dp(12));
        listaClipes.addView(texto("carregando…", 12, 300, MUT));
        b.addView(listaClipes);
        return b;
    }

    // anel de progresso (.ring da pagina)
    class Anel extends View {
        float pct; String txt;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Anel(float pct, String txt) { super(MainActivity.this); this.pct = pct; this.txt = txt; }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), r = w / 2;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(PANEL2); c.drawCircle(r, r, r, p);
            p.setColor(ACENTO); c.drawArc(0, 0, w, w, -90, 3.6f * pct, true, p);
            p.setColor(DIALOGO); c.drawCircle(r, r, r * 0.75f, p);
            p.setColor(FG); p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)); p.setTextSize(dp(10)); p.setTextAlign(android.graphics.Paint.Align.CENTER);
            c.drawText(txt, r, r + dp(3.5f), p);
        }
    }

    String stHost() { return prefs.getString("stHost", ""); }

    // GET simples por socket: e so texto, e assim o app nao precisa liberar HTTP sem criptografia para tudo
    String buscarStatus() throws Exception {
        String user = prefs.getString("stUser", "cambox"), pw = prefs.getString("stPass", "");
        if (pw.isEmpty()) throw new Exception("falta a senha do Moto G no ⚙");
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(stHost(), 8023), 6000);
            s.setSoTimeout(15000);
            String auth = android.util.Base64.encodeToString((user + ":" + pw).getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            s.getOutputStream().write(("GET /cgi-bin/status HTTP/1.0\r\nHost: " + stHost() + "\r\nAuthorization: Basic " + auth + "\r\n\r\n").getBytes("UTF-8"));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            java.io.InputStream in = s.getInputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) { b.write(buf, 0, n); if (b.size() > 400000) break; }
            String r = b.toString("UTF-8");
            if (r.startsWith("HTTP/1.0 401") || r.startsWith("HTTP/1.1 401")) throw new Exception("senha do Moto G recusada");
            int corpo = r.indexOf("\r\n\r\n");
            if (!r.startsWith("HTTP/1.") || !r.substring(9, 12).equals("200") || corpo < 0) throw new Exception("Moto G respondeu " + r.split("\r\n")[0]);
            return r.substring(corpo + 4);
        }
    }

    // rec_20261031_135000 -> hora local (celular e Moto G no mesmo fuso)
    static long carimbo(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})(\\d\\d)(\\d\\d)_(\\d\\d)(\\d\\d)(\\d\\d)").matcher(s == null ? "" : s);
        if (!m.find()) return -1;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
            Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // mesma conta do lerPainel do server.js
    static Map<String, Object> lerPainel(String out) {
        Map<String, Object> r = new HashMap<>();
        String[] linhas = out.split("\r?\n");
        long agora = System.currentTimeMillis();
        int seg = 300, ciclo = 600;
        Map<String, java.util.List<long[]>> arqs = new HashMap<>(), env = new HashMap<>();
        int ffmpeg = 0, envio = 0;
        java.util.regex.Pattern pEnv = java.util.regex.Pattern.compile("^(\\d\\d/\\d\\d \\d\\d:\\d\\d:\\d\\d) enviado: (.+) (\\d{8}_\\d{6}) \\((\\d+) KB\\)");
        for (String l : linhas) {
            if (l.startsWith("ERRO ")) { r.put("erro", l.substring(5)); return r; }
            if (l.startsWith("AGORA ")) agora = Long.parseLong(l.substring(6).trim()) * 1000;
            else if (l.matches("^SEG=\\d+\\s*$")) seg = Integer.parseInt(l.substring(4).trim());
            else if (l.matches("^CICLO=\\d+\\s*$")) ciclo = Integer.parseInt(l.substring(6).trim());
            else if (l.startsWith("ARQ ")) {
                String[] p = l.split(" ");
                if (p.length >= 3 && p[2].matches("rec_\\S+\\.mp4(\\.part)?")) {
                    long ini = carimbo(p[2]);
                    if (ini > 0 && ini <= agora + 60000) arqs.computeIfAbsent(p[1], k -> new java.util.ArrayList<>()).add(new long[] { ini, p.length > 3 && !p[3].isEmpty() ? Long.parseLong(p[3]) : 0 });
                }
            } else if (l.startsWith("PROC ")) {
                String[] p = l.split(" ");
                ffmpeg = Integer.parseInt(p[1]);
                envio = Integer.parseInt(p[2]);
            } else {
                java.util.regex.Matcher m = pEnv.matcher(l);
                if (m.find()) {
                    int k = -1;
                    for (int i = 0; i < NOMES.length; i++) if (NOMES[i].equals(m.group(2))) k = i;
                    long ini = carimbo(m.group(3));
                    if (k >= 0 && ini > 0 && ini <= agora + 60000) env.computeIfAbsent(IDS[k], x -> new java.util.ArrayList<>()).add(new long[] { ini, Long.parseLong(m.group(4)) });
                }
            }
        }
        ciclo = Math.max(seg, ciclo);
        java.util.Calendar hoje = java.util.Calendar.getInstance();
        hoje.setTimeInMillis(agora);
        hoje.set(java.util.Calendar.HOUR_OF_DAY, 0); hoje.set(java.util.Calendar.MINUTE, 0); hoje.set(java.util.Calendar.SECOND, 0); hoje.set(java.util.Calendar.MILLISECOND, 0);
        Map<String, Object> cams = new HashMap<>();
        for (String id : IDS) {
            java.util.List<long[]> lista = arqs.getOrDefault(id, new java.util.ArrayList<>()), e = env.getOrDefault(id, new java.util.ArrayList<>());
            lista.sort((a, b) -> Long.compare(a[0], b[0]));
            e.sort((a, b) -> Long.compare(a[0], b[0]));
            // o clipe enviado e apagado do celular, entao o mais novo pode estar so no log de envio
            long[] ultArq = lista.isEmpty() ? null : lista.get(lista.size() - 1), ultEnv = e.isEmpty() ? null : e.get(e.size() - 1);
            long[] atual = ultArq != null && (ultEnv == null || ultArq[0] >= ultEnv[0]) ? ultArq : ultEnv != null ? new long[] { ultEnv[0], ultEnv[1] * 1024 } : null;
            Map<String, Object> c = new HashMap<>();
            String estado = "parado";
            if (atual != null) {
                long fim = atual[0] + seg * 1000L;
                // passou 2 min da volta prevista sem clipe novo = gravacao travada ou camera fora
                estado = agora < fim ? "gravando" : agora < atual[0] + (ciclo + 120) * 1000L ? "pausa" : "parado";
                c.put("ini", atual[0]); c.put("fim", fim);
                c.put("pct", (int) Math.max(0, Math.min(100, Math.round((agora - atual[0]) / (seg * 10.0)))));
                c.put("proxIni", atual[0] + ciclo * 1000L);
                c.put("proxEnvio", atual[0] + (ciclo + seg) * 1000L);
            }
            c.put("estado", estado);
            int esperando = 0;
            for (long[] a : lista) if (a != ultArq || !estado.equals("gravando")) esperando++;
            c.put("esperando", esperando);
            int nHoje = 0;
            for (long[] x : e) if (x[0] >= hoje.getTimeInMillis()) nHoje++;
            c.put("hoje", nHoje);
            java.util.List<long[]> ult = new java.util.ArrayList<>();
            for (int i = e.size() - 1; i >= 0 && ult.size() < 3; i--) ult.add(new long[] { e.get(i)[0], e.get(i)[0] + seg * 1000L, e.get(i)[1] });
            c.put("ultimos", ult);
            cams.put(id, c);
        }
        r.put("agora", agora); r.put("seg", seg); r.put("ciclo", ciclo); r.put("ffmpeg", ffmpeg); r.put("envio", envio > 0); r.put("cams", cams);
        return r;
    }

    void carregarGravacao() {
        rede.post(() -> {
            Map<String, Object> g;
            try {
                g = lerPainel(buscarStatus());
            } catch (Exception e) { g = new HashMap<>(); g.put("erro", e.getMessage()); }
            final Map<String, Object> f = g;
            ui.post(() -> {
                grav = f;
                if (f.containsKey("agora")) gravSkew = (Long) f.get("agora") - System.currentTimeMillis();
                desenharGravacao();
            });
        });
    }

    final Runnable pesquisaGrav = new Runnable() {   // a cada 30 s, so com o app na tela
        @Override public void run() { carregarGravacao(); ui.postDelayed(this, 30000); }
    };

    final Runnable relogioGrav = new Runnable() {
        @Override public void run() { tickGrav(); ui.postDelayed(this, 1000); }
    };

    @SuppressWarnings("unchecked")
    Map<String, Object> gCam() {
        Map<String, Object> g = grav;
        if (g == null || !g.containsKey("cams")) return null;
        return (Map<String, Object>) ((Map<String, Object>) g.get("cams")).get(IDS[cam()]);
    }

    String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t)); }
    static String dois(long n) { return n < 10 ? "0" + n : String.valueOf(n); }

    @SuppressWarnings("unchecked")
    void desenharGravacao() {
        Map<String, Object> g = grav;
        listaClipes.removeAllViews();
        if (g == null) return;
        if (g.containsKey("erro")) {
            TextView t = texto("Moto G: " + g.get("erro"), 12, 300, MUT);
            listaClipes.addView(t);
            gravMsg.setText("Sem status do Moto G: " + g.get("erro"));
            gravMsg.setVisibility(View.VISIBLE);
            return;
        }
        gravSub.setText("Clipes de " + ((Integer) g.get("seg") / 60) + " minutos");
        int online = 0;
        for (String id : IDS) if (!"parado".equals(((Map<String, Object>) ((Map<String, Object>) g.get("cams")).get(id)).get("estado"))) online++;
        numCam.setText(online + "/" + IDS.length);
        Map<String, Object> c = gCam();
        numFps.setText(String.valueOf(c.get("hoje")));
        java.util.List<long[]> ult = (java.util.List<long[]>) c.get("ultimos");
        String nome = NOMES[cam()];
        java.util.List<Object[]> linhas = new java.util.ArrayList<>();
        for (int i = Math.min(2, ult.size()) - 1; i >= 0; i--) {
            long[] u = ult.get(i);
            linhas.add(new Object[] { hhmm(u[0]) + " → " + hhmm(u[1]), nome + " · " + String.format(Locale.US, "%.1f", u[2] / 1024.0) + " MB", 100f, "OK" });
        }
        String est = (String) c.get("estado");
        int esp = (Integer) c.get("esperando");
        if (est.equals("pausa")) linhas.add(new Object[] { "Em pausa · próximo clipe às " + hhmm((Long) c.get("proxIni")), nome + (esp > 0 ? " · " + esp + " na fila" : ""), null, null });
        else if (est.equals("gravando")) linhas.add(new Object[] { hhmm((Long) c.get("ini")) + " → agora", "Gravando · envia às " + hhmm((Long) c.get("fim")) + (esp > 0 ? " · " + esp + " na fila" : ""),
            (float) (Integer) c.get("pct"), c.get("pct") + "%" });
        if (linhas.isEmpty()) listaClipes.addView(texto("Nenhum clipe desta câmera ainda.", 12, 300, MUT), lp(-1, -2, 0, 0, 6, 0, 6));
        for (int i = 0; i < linhas.size(); i++) {
            Object[] x = linhas.get(i);
            if (i > 0) { View sep = new View(this); sep.setBackgroundColor(LINHA_SUAVE); listaClipes.addView(sep, new LinearLayout.LayoutParams(-1, dp(1))); }
            LinearLayout row = linha(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            TextView n = texto(dois(i + 1), 26, 700, G2);
            numerico(n);
            row.addView(n, lp(-2, -2, 0, 0, 0, 12, 0));
            View barra = new View(this);
            barra.setBackgroundColor(LINHA);
            row.addView(barra, lp(dp(2), dp(30), 0, 0, 0, 12, 0));
            LinearLayout tx = coluna();
            tx.addView(rotulo((String) x[0], 12, FG, 0));
            tx.addView(texto((String) x[1], 11, 300, MUT), lp(-2, -2, 0, 0, 2, 0, 0));
            row.addView(tx, lp(0, -2, 1, 0, 0, 8, 0));
            if (x[2] != null) row.addView(new Anel((Float) x[2], (String) x[3]), new LinearLayout.LayoutParams(dp(40), dp(40)));
            listaClipes.addView(row);
        }
        boolean gravando = !est.equals("parado");
        String m = !gravando ? "Gravação parada nesta câmera (câmera desligada ou sem rede)." : ((Boolean) g.get("envio") ? "" : "Atenção: o envio ao Telegram não está rodando no celular.");
        gravMsg.setText(m);
        gravMsg.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);
        tickGrav();
    }

    void tickGrav() {
        Map<String, Object> c = gCam();
        TextView faltaRot = (TextView) ((LinearLayout) gravFalta.getParent()).getChildAt(1);
        boolean pausa = c != null && "pausa".equals(c.get("estado"));
        faltaRot.setText(pausa ? "PAUSA · VOLTA EM" : "FALTA");
        if (c == null || "parado".equals(c.get("estado"))) { gravProx.setText("--:--"); gravFalta.setText("--:--"); if (c != null) numImagem.setText("—"); return; }
        long agora = System.currentTimeMillis() + gravSkew;
        long alvo = pausa ? (Long) c.get("proxIni") : (Long) c.get("fim");
        long resta = Math.max(0, alvo - agora);
        gravProx.setText(hhmm(pausa ? (Long) c.get("proxEnvio") : (Long) c.get("fim")));
        gravFalta.setText(dois(resta / 60000) + ":" + dois((resta / 1000) % 60));
        int seg = (Integer) grav.get("seg");
        numImagem.setText(pausa ? "pausa" : Math.max(0, Math.min(100, Math.round((agora - (Long) c.get("ini")) / (seg * 10.0)))) + "%");
        if (resta == 0 && !tickPedido) { tickPedido = true; ui.postDelayed(() -> { tickPedido = false; carregarGravacao(); }, 20000); }
    }
    boolean tickPedido;

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(pesquisaGrav);
        ui.removeCallbacks(relogioGrav);
        ui.post(pesquisaGrav);
        ui.post(relogioGrav);
    }

    // .act da pagina: faixa magenta a esquerda, icone, texto e bolinha de "ligado" a direita
    View acao(LinearLayout pai, String icone, TextView rot, String txt, View.OnClickListener c) {
        LinearLayout a = linha(Gravity.CENTER_VERTICAL);
        a.setBackground(caixa(PANEL, 12, 0, 0));
        a.setClipToOutline(true);
        View faixa = new View(this);
        faixa.setBackgroundColor(G2);
        a.addView(faixa, new LinearLayout.LayoutParams(dp(4), -1));
        TextView ic = texto(icone, 18, 400, ACENTO);
        a.addView(ic, lp(-2, -2, 0, 12, 0, 12, 0));
        rot.setText(txt);
        rot.setTextSize(13);
        rot.setTypeface(fonte(500));
        rot.setTextColor(FG);
        a.addView(rot, lp(0, -2, 1, 0, 0, 0, 0));
        View ck = new View(this);
        ck.setBackground(redondo(Color.TRANSPARENT, INFO, 2));
        a.addView(ck, lp(dp(22), dp(22), 0, 0, 0, 14, 0));
        a.setOnClickListener(c);
        pai.addView(a, lp(-1, dp(50), 0, 0, 0, 0, 10));
        return ck;
    }

    void marcar(View ck, boolean on, int cor) {
        ck.setBackground(on ? redondo(cor, cor, 2) : redondo(Color.TRANSPARENT, INFO, 2));
    }

    void avisoSom(String s) {
        ui.post(() -> { msgSom.setText(s); msgSom.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE); });
    }

    // Ouvir: o som ja vem no RTSP (A-law 8 kHz); aqui so decodifica e toca. Volume = botoes de volume do celular.
    void alternarOuvir() {
        if (faixa != null) {
            android.media.AudioTrack f = faixa;
            faixa = null;
            f.stop();
            f.release();
            rotOuvir.setText("Ouvir áudio ao vivo");
            marcar(checkOuvir, false, INFO);
            return;
        }
        int min = android.media.AudioTrack.getMinBufferSize(8000, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
        android.media.AudioTrack f = new android.media.AudioTrack.Builder()
            .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(8000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(Math.max(min, 8000 * 2 / 2))   // ~0,5 s: segura a variacao da rede sem atrasar muito
            .setTransferMode(android.media.AudioTrack.MODE_STREAM).build();
        f.play();
        faixa = f;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        rotOuvir.setText("Parar som");
        marcar(checkOuvir, true, INFO);
    }

    void tocar(byte[] alaw, int off, int len) {
        android.media.AudioTrack f = faixa;
        if (f == null || falando) return;   // falando: nao toca, senao o celular escuta a propria voz de volta
        short[] pcm = new short[len];
        for (int i = 0; i < len; i++) pcm[i] = Alaw.decodifica(alaw[off + i]);
        try { f.write(pcm, 0, len, android.media.AudioTrack.WRITE_NON_BLOCKING); } catch (Exception e) { }
    }

    // Falar: microfone -> A-law 8 kHz -> conexao DVRIP propria (OPTalk), igual ao "Falar" da pagina
    void alternarFala() {
        if (tocandoSom) { avisar("Pare o som antes de falar."); return; }
        if (falando) {
            falando = false;
            rotFalar.setText("Falar pela câmera");
            marcar(checkFalar, false, INFO);
            avisoSom("");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.RECORD_AUDIO }, 7);
            return;
        }
        falando = true;
        rotFalar.setText("Falando… toque para parar");
        marcar(checkFalar, true, RUIM);
        avisoSom("Conectando o alto-falante…");
        final int c = cam();
        falador = new Thread(() -> {
            Dvrip d = new Dvrip();
            android.media.AudioRecord mic = null;
            try {
                d.conectar(host(c), user(c), pass(c));
                d.abrirFala();
                int min = android.media.AudioRecord.getMinBufferSize(8000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
                mic = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 320 * 2 * 4));
                mic.startRecording();
                avisoSom("Pode falar: a câmera está transmitindo sua voz.");
                short[] pcm = new short[320];
                byte[] alaw = new byte[320];
                while (falando) {   // 320 amostras = 40 ms; o read segura o ritmo certo
                    int n = 0;
                    while (n < 320 && falando) { int r = mic.read(pcm, n, 320 - n); if (r <= 0) break; n += r; }
                    if (n < 320) break;
                    for (int i = 0; i < 320; i++) alaw[i] = Alaw.codifica((short) (pcm[i] / 2));   // ganho 0,5 como na pagina
                    d.falar(alaw, 320);
                }
            } catch (Exception e) {
                avisoSom("Não consegui falar: " + e.getMessage());
                ui.post(() -> { if (falando) { falando = false; rotFalar.setText("Falar pela câmera"); marcar(checkFalar, false, INFO); } });
            } finally {
                if (mic != null) { try { mic.stop(); } catch (Exception e) { } mic.release(); }
                d.fechar();   // fechar o socket encerra a conversa na camera
            }
        }, "falar");
        falador.start();
    }

    // ---------- sons: mesma conversa do Falar, trocando o microfone por um som guardado no app ----------
    static final int PEDIDO_AUDIO = 8;
    volatile boolean tocandoSom;
    volatile int somGeracao;

    java.io.File pastaSons() { java.io.File p = new java.io.File(getFilesDir(), "sons"); p.mkdirs(); return p; }

    void menuSons() {
        if (falando) { avisar("Pare de falar antes de tocar um som."); return; }
        Dialog d = new Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout l = coluna();
        l.setBackground(caixa(DIALOGO, 18, LINHA, 1));
        l.setPadding(dp(18), dp(16), dp(18), dp(16));
        l.addView(rotulo("Sons", 13, FG, 0.06f));
        l.addView(texto("Toque para a " + NOMES[cam()] + " tocar.", 11, 300, INFO), lp(-2, -2, 0, 0, 3, 0, 6));
        String[] prontos;   // sons que vieram no APK: assets/sons/*.al (ver README)
        try { prontos = getAssets().list("sons"); } catch (Exception e) { prontos = new String[0]; }
        if (prontos == null) prontos = new String[0];
        java.util.Arrays.sort(prontos);
        for (String arqSom : prontos) {
            if (!arqSom.endsWith(".al")) continue;
            final String arqAl = arqSom, rot = Sons.rotulo(arqSom);
            l.addView(pilula(rot, true, v -> {
                d.dismiss();
                try (InputStream in = getAssets().open("sons/" + arqAl)) { tocarSom(lerTudo(in), rot); }
                catch (Exception e) { avisoSom("Não consegui abrir o som: " + e.getMessage()); }
            }), lp(-1, dp(40), 0, 0, 8, 0, 0));
        }
        java.io.File[] salvos = pastaSons().listFiles();
        if (salvos != null && salvos.length > 0) {
            java.util.Arrays.sort(salvos);
            l.addView(rotulo("Do celular", 10, MUT, 0.1f), lp(-2, -2, 0, 0, 14, 0, 0));
            for (java.io.File arq : salvos) {
                String nome = arq.getName().replaceAll("\\.al$", "");
                LinearLayout fila = linha(Gravity.CENTER_VERTICAL);
                fila.addView(pilula(nome, true, v -> {
                    d.dismiss();
                    try (InputStream in = new java.io.FileInputStream(arq)) { tocarSom(lerTudo(in), nome); }
                    catch (Exception e) { avisoSom("Não consegui abrir o som: " + e.getMessage()); }
                }), lp(0, dp(40), 1, 0, 0, 8, 0));
                Button apagar = pilula("Apagar", false, v -> { arq.delete(); d.dismiss(); menuSons(); });
                apagar.setContentDescription("Apagar " + nome);
                fila.addView(apagar, lp(dp(76), dp(40), 0, 0, 0, 0, 0));
                l.addView(fila, lp(-1, -2, 0, 0, 8, 0, 0));
            }
        }
        l.addView(pilula("Escolher do celular", false, v -> {
            d.dismiss();
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.setType("audio/*");
            startActivityForResult(it, PEDIDO_AUDIO);
        }), lp(-1, dp(40), 0, 0, 14, 0, 0));
        l.addView(pilula("Fechar", false, v -> d.dismiss()), lp(-1, dp(38), 0, 0, 8, 0, 0));
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        d.setContentView(sv);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.show();
    }

    static byte[] lerTudo(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    void tocarSom(byte[] alaw, String nome) {
        if (falando) { avisar("Pare de falar antes de tocar um som."); return; }
        final int g = ++somGeracao, c = cam();
        tocandoSom = true;
        rotSons.setText("Tocando " + nome + "… toque para parar");
        marcar(checkSons, true, RUIM);
        avisoSom("Conectando o alto-falante…");
        new Thread(() -> {
            Dvrip d = new Dvrip();
            String erro = null;
            try {
                d.conectar(host(c), user(c), pass(c));
                d.abrirFala();
                avisoSom("");
                byte[] q = new byte[320];
                long t0 = System.nanoTime();
                for (int k = 0, pos = 0; pos < alaw.length && g == somGeracao; k++, pos += 320) {
                    int n = Math.min(320, alaw.length - pos);
                    System.arraycopy(alaw, pos, q, 0, n);
                    java.util.Arrays.fill(q, n, 320, Alaw.codifica((short) 0));   // fim do som: completa com silencio
                    d.falar(q, 320);
                    long espera = t0 + (k - 4) * 40_000_000L - System.nanoTime();   // 5 quadros (0,2 s) de folga na camera
                    if (espera > 0) Thread.sleep(espera / 1_000_000L);
                }
                if (g == somGeracao) Thread.sleep(300);   // deixa sair o que ainda esta na folga antes de fechar
            } catch (InterruptedException e) {
            } catch (Exception e) { erro = e.getMessage(); }
            finally { d.fechar(); }
            final String er = erro;
            ui.post(() -> {
                if (g != somGeracao) return;
                fimSom();
                if (er != null) avisoSom("Não consegui tocar: " + er);
            });
        }, "som").start();
    }

    void fimSom() {
        tocandoSom = false;
        rotSons.setText("Sons");
        marcar(checkSons, false, INFO);
    }

    void pararSom() {
        if (!tocandoSom) return;
        somGeracao++;
        fimSom();
        avisoSom("");
    }

    @Override protected void onActivityResult(int pedido, int res, android.content.Intent dados) {
        super.onActivityResult(pedido, res, dados);
        if (pedido != PEDIDO_AUDIO || res != RESULT_OK || dados == null || dados.getData() == null) return;
        Uri u = dados.getData();
        avisoSom("Preparando o som…");
        new Thread(() -> {
            try {
                byte[] alaw = Sons.paraAlaw(Sons.normalizar(decodificarAudio(u)));
                String nome = Sons.nomeSeguro(nomeDoArquivo(u));
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(pastaSons(), nome + ".al"))) { o.write(alaw); }
                ui.post(() -> tocarSom(alaw, nome));
            } catch (Exception e) { avisoSom("Não consegui abrir esse arquivo: " + e.getMessage()); }
        }, "decodificar").start();
    }

    String nomeDoArquivo(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) { }
        return null;
    }

    // qualquer audio que o Android abra -> mono 8 kHz; so le ate o limite de duracao
    short[] decodificarAudio(Uri u) throws Exception {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaCodec dec = null;
        try {
            ex.setDataSource(this, u, null);
            android.media.MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount() && fmt == null; i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                if (f.getString(android.media.MediaFormat.KEY_MIME).startsWith("audio/")) { fmt = f; ex.selectTrack(i); }
            }
            if (fmt == null) throw new IOException("o arquivo não tem áudio");
            int taxa = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE), canais = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
            dec = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            short[] pcm = new short[taxa * 10];
            int n = 0;
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            boolean fimEntrada = false, fimSaida = false;
            while (!fimSaida && n < (long) taxa * Sons.MAX_SEG) {
                if (!fimEntrada) {
                    int e = dec.dequeueInputBuffer(10000);
                    if (e >= 0) {
                        int t = ex.readSampleData(dec.getInputBuffer(e), 0);
                        if (t < 0) { dec.queueInputBuffer(e, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); fimEntrada = true; }
                        else { dec.queueInputBuffer(e, 0, t, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int s = dec.dequeueOutputBuffer(info, 10000);
                if (s == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.media.MediaFormat o = dec.getOutputFormat();
                    taxa = o.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                    canais = o.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
                } else if (s >= 0) {
                    java.nio.ShortBuffer sb = dec.getOutputBuffer(s).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                    int quadros = (info.size / 2) / Math.max(1, canais);
                    if (n + quadros > pcm.length) pcm = java.util.Arrays.copyOf(pcm, Math.max(pcm.length * 2, n + quadros));
                    for (int q = 0; q < quadros; q++) {
                        int soma = 0;
                        for (int k = 0; k < canais; k++) soma += sb.get();
                        pcm[n++] = (short) (soma / canais);
                    }
                    dec.releaseOutputBuffer(s, false);
                    if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) fimSaida = true;
                }
            }
            if (n == 0) throw new IOException("não saiu áudio do arquivo");
            return Sons.reamostrar(pcm, n, taxa);
        } finally {
            if (dec != null) { try { dec.stop(); } catch (Exception e) { } dec.release(); }
            ex.release();
        }
    }

    @Override public void onRequestPermissionsResult(int pedido, String[] perms, int[] res) {
        if (pedido == 7 && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) alternarFala();
        else if (pedido == 7) avisar("Sem permissão do microfone não dá para falar.");
    }

    // ---------- foto ----------
    void foto() {
        Bitmap bm = tela.getBitmap();
        if (bm == null || dec == null || dec.quadros == 0) { avisar("Ainda sem imagem"); return; }
        if (Build.VERSION.SDK_INT < 29) { avisar("Foto precisa de Android 10 ou mais novo"); return; }
        String nome = "CAMhouse_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, nome);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CAM house");
        Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        try (OutputStream o = getContentResolver().openOutputStream(u)) {
            bm.compress(Bitmap.CompressFormat.JPEG, 92, o);
            avisar("Foto salva na galeria (Pictures/CAM house)");
        } catch (Exception e) { avisar("Não salvou a foto: " + e.getMessage()); }
    }

    // ---------- tela cheia ----------
    void telaCheia(boolean ligar) {
        cheia = ligar;
        setRequestedOrientation(ligar ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        int fora = ligar ? View.GONE : View.VISIBLE;
        heroi.setVisibility(fora);
        abas.setVisibility(fora);
        ferramentas.setVisibility(fora);
        blocoPtz.setVisibility(fora);
        blocoSom.setVisibility(fora);
        blocoAjustes.setVisibility(fora);
        blocoClipes.setVisibility(fora);
        ((View) pagina.getChildAt(pagina.getChildCount() - 1)).setVisibility(fora);   // rodape
        ((View) ferramentas.getParent()).setPadding(ligar ? 0 : dp(16), ligar ? 0 : dp(12), ligar ? 0 : dp(16), ligar ? 0 : dp(16));
        View dica = ((LinearLayout) ferramentas.getParent()).getChildAt(2);
        dica.setVisibility(fora);
        pagina.setPadding(ligar ? 0 : dp(16), ligar ? 0 : dp(12), ligar ? 0 : dp(16), ligar ? 0 : dp(16));
        blocoVideo.setBackground(ligar ? new ColorDrawable(Color.BLACK) : caixa(PANEL, 18, LINHA, 1));
        palco.setBackground(caixa(Color.BLACK, ligar ? 0 : 12, 0, 0));
        flutuante.setVisibility(ligar ? View.VISIBLE : View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(ligar
            ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            : 0);
        palco.setLayoutParams(new LinearLayout.LayoutParams(-1, ligar ? getResources().getDisplayMetrics().heightPixels : dp(180)));
        rolagem.post(() -> { ajustarTamanhoCheia(); ajustarAlturaVideo(); aplicarMatriz(); });
    }

    void ajustarTamanhoCheia() {
        if (!cheia) return;
        palco.setLayoutParams(new LinearLayout.LayoutParams(-1, rolagem.getHeight()));
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        rolagem.post(() -> { ajustarTamanhoCheia(); ajustarAlturaVideo(); aplicarMatriz(); });
    }

    @Override public void onBackPressed() { if (cheia) telaCheia(false); else super.onBackPressed(); }

    // ---------- zoom digital (pinca, arrastar, duplo toque volta) ----------
    void gestos() {
        ScaleGestureDetector pinca = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float z = Math.max(1, Math.min(6, zoom * d.getScaleFactor()));
                float k = z / zoom;   // mantem o ponto entre os dedos parado
                float cx = d.getFocusX() - tela.getWidth() / 2f, cy = d.getFocusY() - tela.getHeight() / 2f;
                panX = cx - (cx - panX) * k;
                panY = cy - (cy - panY) * k;
                zoom = z;
                aplicarMatriz();
                return true;
            }
        });
        GestureDetector arrasto = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                panX -= dx; panY -= dy; aplicarMatriz(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { zoom = 1; panX = panY = 0; aplicarMatriz(); return true; }
        });
        tela.setOnTouchListener((v, e) -> {
            if (e.getPointerCount() > 1 || zoom > 1) v.getParent().requestDisallowInterceptTouchEvent(true);   // nao rola a pagina
            pinca.onTouchEvent(e);
            arrasto.onTouchEvent(e);
            return true;
        });
    }

    void aplicarMatriz() {
        float w = tela.getWidth(), h = tela.getHeight();
        if (w == 0 || h == 0) return;
        float k = Math.min(w / vidW, h / vidH);   // cabe inteiro, sem esticar
        float cw = vidW * k * zoom, ch = vidH * k * zoom;
        float mx = Math.max(0, (cw - w) / 2), my = Math.max(0, (ch - h) / 2);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
        Matrix m = new Matrix();
        m.setScale(vidW * k / w * zoom, vidH * k / h * zoom, w / 2, h / 2);
        m.postTranslate(panX, panY);
        tela.setTransform(m);
        chipZoom.setText(String.format(Locale.US, "%.1f×", zoom));
    }

    // ---------- video ----------
    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        superficie = new Surface(st);
        aplicarMatriz();
        iniciar();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { aplicarMatriz(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { parar(); superficie = null; return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    void reiniciar() { parar(); iniciar(); }

    void iniciar() {
        int c = cam();
        if (superficie == null || user(c).isEmpty()) return;
        String caminho = "user=" + user(c) + "&password=" + pass(c) + "&channel=1&stream=" + stream() + ".sdp";
        final int g = ++geracao;
        dec = new Decodificador(superficie, g);
        rtsp = new RtspHevc(host(c), 554, caminho, dec);
        new Thread(rtsp, "rtsp").start();
        vivo("Conectando", CONECTANDO);
    }

    void parar() {
        geracao++;
        if (rtsp != null) rtsp.parar();
        rtsp = null;
        if (dec != null) dec.fechar();
        dec = null;
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(pesquisaGrav);   // status do Moto G so com o app na tela
        ui.removeCallbacks(relogioGrav);
        if (falando) alternarFala();   // nunca deixa o microfone aberto com o app fechado
        pararSom();
        for (String c : new String[] { "DirectionUp", "DirectionDown", "DirectionLeft", "DirectionRight", "ZoomTile", "ZoomWide" })
            ptz(c, false);   // nunca deixa a camera girando com o app fechado
    }

    @Override protected void onDestroy() {
        falando = false;
        somGeracao++;
        if (faixa != null) alternarOuvir();
        parar();
        rede.post(dvrip::fechar);
        rede.getLooper().quitSafely();
        super.onDestroy();
    }

    // Junta os NALs de cada quadro e entrega ao decodificador de hardware (H.265), desenhando na hora.
    class Decodificador implements RtspHevc.Saida {
        final Surface surf;
        final int g;
        final ByteArrayOutputStream quadro = new ByteArrayOutputStream();
        byte[] vps, sps, pps;
        MediaCodec codec;
        boolean temChave, ehChave;
        volatile long quadros;
        long ultimaConta = System.currentTimeMillis(), contaQuadros;
        volatile boolean fechado;

        Decodificador(Surface surf, int g) { this.surf = surf; this.g = g; }

        @Override public void som(byte[] alaw, int off, int len) { if (!fechado) tocar(alaw, off, len); }

        @Override public void estado(String s) {
            if (fechado) return;
            if (!s.startsWith("erro")) return;
            vivo(s.contains("401") ? "Senha recusada" : "Sem sinal", FALHA);
            msgPtz(s);
            // tenta de novo sozinho, menos com senha recusada: repetir login errado pode bloquear a camera
            if (!s.contains("401")) ui.postDelayed(() -> { if (g == geracao) reiniciar(); }, 3000);
        }

        @Override public void nal(byte[] b, int off, int len, long pts, boolean fim) {
            if (fechado || len < 2) return;
            int tipo = (b[off] >> 1) & 0x3f;
            if (tipo == 32) vps = copia(b, off, len);
            if (tipo == 33) sps = copia(b, off, len);
            if (tipo == 34) pps = copia(b, off, len);
            if (tipo >= 16 && tipo <= 21) ehChave = true;   // quadro completo (IRAP)
            quadro.write(0); quadro.write(0); quadro.write(0); quadro.write(1);
            quadro.write(b, off, len);
            if (fim) {
                byte[] q = quadro.toByteArray();
                quadro.reset();
                boolean chave = ehChave;
                ehChave = false;
                try { decodificar(q, pts, chave); } catch (Exception e) { if (!fechado) msgPtz("erro no decodificador: " + e.getMessage()); }
            }
        }

        byte[] copia(byte[] b, int off, int len) { byte[] c = new byte[len]; System.arraycopy(b, off, c, 0, len); return c; }

        synchronized void decodificar(byte[] q, long pts, boolean chave) throws Exception {
            if (fechado) return;
            if (codec == null) {
                if (vps == null || sps == null || pps == null) return;
                ByteArrayOutputStream csd = new ByteArrayOutputStream();
                for (byte[] n : new byte[][] { vps, sps, pps }) { csd.write(new byte[] { 0, 0, 0, 1 }); csd.write(n); }
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080);
                f.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()));
                if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
                codec.configure(f, surf, null, 0);
                codec.start();
            }
            if (!temChave) { if (!chave) return; temChave = true; }   // comeca so num quadro completo
            int i = codec.dequeueInputBuffer(20000);
            if (i >= 0) {
                ByteBuffer bb = codec.getInputBuffer(i);
                bb.clear();
                if (q.length <= bb.capacity()) { bb.put(q); codec.queueInputBuffer(i, 0, q.length, pts, 0); }
                else codec.queueInputBuffer(i, 0, 0, pts, 0);
            }
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int o;
            while ((o = codec.dequeueOutputBuffer(bi, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    int w = of.getInteger(MediaFormat.KEY_WIDTH), h = of.getInteger(MediaFormat.KEY_HEIGHT);
                    if (of.containsKey("crop-right")) { w = of.getInteger("crop-right") + 1; h = of.getInteger("crop-bottom") + 1; }
                    final int fw = w, fh = h;
                    ui.post(() -> { vidW = fw; vidH = fh; aplicarMatriz(); });
                } else if (o >= 0) {
                    codec.releaseOutputBuffer(o, true);   // desenha ja, sem fila: menor atraso
                    if (quadros++ == 0) vivo("Ao vivo", AO_VIVO);
                    contaQuadros++;
                }
            }
            long agora = System.currentTimeMillis();
            if (agora - ultimaConta >= 2000) {
                final String fps = String.format(Locale.US, "%.1f", contaQuadros * 1000f / (agora - ultimaConta));
                contaQuadros = 0;
                ultimaConta = agora;
            }
        }

        synchronized void fechar() {
            fechado = true;
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception e) { }
            codec = null;
        }
    }
}
