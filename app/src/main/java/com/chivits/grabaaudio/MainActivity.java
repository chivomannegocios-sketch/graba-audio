package com.chivits.grabaaudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pantalla principal: botón grande para grabar el audio interno, nivel en vivo y lista de grabaciones. */
public class MainActivity extends Activity {

    private static final int FONDO = Color.rgb(13, 15, 20);
    private static final int TARJETA = Color.rgb(24, 27, 34);
    private static final int BORDE = Color.rgb(40, 44, 54);
    private static final int ROJO = Color.rgb(239, 68, 68);
    private static final int VERDE = Color.rgb(52, 199, 89);
    private static final int AMARILLO = Color.rgb(250, 204, 21);
    private static final int BLANCO = Color.rgb(240, 242, 245);
    private static final int GRIS = Color.rgb(150, 156, 168);
    private static final int AZUL = Color.rgb(64, 156, 255);

    private static final int PEDIR_CAPTURA = 10;
    private static final int PEDIR_PERMISOS = 11;

    private float d;
    private SharedPreferences sp;
    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView reloj, estado, botonGrabar, botonPausa, aviso;
    private View barraNivel;
    private FrameLayout cajaNivel;
    private View circuloM4a, circuloWav;
    private LinearLayout lista;
    private int cambiosVistos = -1;
    private boolean estabaGrabando = false;

    private MediaPlayer reproductor;
    private long sonandoId = -1;
    private TextView botonSonando;

    private final Runnable refresco = new Runnable() {
        @Override
        public void run() {
            actualizar();
            h.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        sp = getSharedPreferences("datos", MODE_PRIVATE);
        Window w = getWindow();
        w.setStatusBarColor(FONDO);
        w.setNavigationBarColor(FONDO);

        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setPadding(dp(16), dp(28), dp(16), dp(32));
        caja.setBackgroundColor(FONDO);

        TextView titulo = texto("Graba Audio", 26, BLANCO);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        caja.addView(titulo);
        caja.addView(texto("Graba el sonido del celular, sin micrófono", 14, GRIS));
        espacio(caja, 16);

        // ---------- grabadora ----------
        LinearLayout g = tarjeta(caja);
        g.setGravity(Gravity.CENTER_HORIZONTAL);
        reloj = texto("00:00", 54, BLANCO);
        reloj.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        reloj.setGravity(Gravity.CENTER);
        g.addView(reloj);
        estado = texto("Listo para grabar", 14, GRIS);
        estado.setGravity(Gravity.CENTER);
        g.addView(estado);
        espacio(g, 12);

        cajaNivel = new FrameLayout(this);
        GradientDrawable fn = new GradientDrawable();
        fn.setColor(Color.rgb(36, 40, 50));
        fn.setCornerRadius(dp(5));
        cajaNivel.setBackground(fn);
        barraNivel = new View(this);
        GradientDrawable bn = new GradientDrawable();
        bn.setColor(VERDE);
        bn.setCornerRadius(dp(5));
        barraNivel.setBackground(bn);
        cajaNivel.addView(barraNivel, new FrameLayout.LayoutParams(0, dp(10)));
        g.addView(cajaNivel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
        TextView tn = texto("Nivel del sonido", 11, GRIS);
        tn.setGravity(Gravity.CENTER);
        g.addView(tn);
        espacio(g, 16);

        botonGrabar = texto("●  Grabar", 20, Color.WHITE);
        botonGrabar.setTypeface(Typeface.DEFAULT_BOLD);
        botonGrabar.setGravity(Gravity.CENTER);
        botonGrabar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (GrabarService.grabando) mandar(GrabarService.PARAR);
                else empezar();
            }
        });
        g.addView(botonGrabar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
        espacio(g, 10);
        botonPausa = texto("❚❚  Pausar", 16, BLANCO);
        botonPausa.setGravity(Gravity.CENTER);
        botonPausa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mandar(GrabarService.PAUSAR);
            }
        });
        g.addView(botonPausa, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        aviso = texto("", 13, AMARILLO);
        aviso.setGravity(Gravity.CENTER);
        aviso.setVisibility(View.GONE);
        aviso.setPadding(0, dp(12), 0, 0);
        g.addView(aviso);

        // ---------- formato ----------
        LinearLayout f = tarjeta(caja);
        f.addView(subtitulo("🎚  Calidad"));
        circuloM4a = opcion(f, "M4A — Muy buena calidad", "Pesa poco (≈1,4 MB por minuto). Recomendado.", false);
        circuloWav = opcion(f, "WAV — Calidad máxima, sin comprimir", "Igual al original, pero pesa ≈10 MB por minuto.", true);

        // ---------- grabaciones ----------
        LinearLayout gl = tarjeta(caja);
        gl.addView(subtitulo("🎧  Mis grabaciones"));
        gl.addView(nota("Se guardan en tu celular en Música > GrabaAudio."));
        espacio(gl, 6);
        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        gl.addView(lista);

        // ---------- ayuda ----------
        LinearLayout ay = tarjeta(caja);
        ay.addView(subtitulo("💡  Cómo usar"));
        ay.addView(nota("1. Toca Grabar y acepta el permiso (elige \"Pantalla completa\" si te pregunta).\n"
                + "2. Abre el video, la música o el juego. Se graba solo el sonido, sin ruido de afuera.\n"
                + "3. Para terminar, vuelve aquí o usa \"Parar y guardar\" en la notificación.\n\n"
                + "Algunas apps no dejan grabar su sonido (Netflix, Spotify, llamadas): ahí saldrá en silencio. "
                + "Puedes bajar el volumen del celular: la grabación sale igual de fuerte."));

        TextView pie = texto("Hecho por CHlVOMAN · v1.0", 12, GRIS);
        pie.setGravity(Gravity.CENTER);
        pie.setPadding(0, dp(18), 0, 0);
        caja.addView(pie);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(FONDO);
        sv.addView(caja);
        setContentView(sv);
        marcarFormato();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cambiosVistos = -1;
        h.post(refresco);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresco);
    }

    @Override
    protected void onDestroy() {
        pararReproduccion();
        super.onDestroy();
    }

    // ===================== grabar =====================

    private void empezar() {
        List<String> faltan = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            faltan.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            faltan.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!faltan.isEmpty()) {
            requestPermissions(faltan.toArray(new String[0]), PEDIR_PERMISOS);
            return;
        }
        pararReproduccion();
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), PEDIR_CAPTURA);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Este celular no permite capturar el audio", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != PEDIR_PERMISOS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            empezar();
        } else {
            Toast.makeText(this, "Necesito el permiso de audio para poder grabar", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code != PEDIR_CAPTURA) return;
        if (res != RESULT_OK || data == null) {
            Toast.makeText(this, "Grabación cancelada", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, GrabarService.class);
        i.putExtra("codigo", res);
        i.putExtra("datos", data);
        i.putExtra("wav", sp.getBoolean("wav", false));
        GrabarService.mensaje = null;
        startForegroundService(i);
    }

    private void mandar(String accion) {
        startService(new Intent(this, GrabarService.class).setAction(accion));
    }

    // ===================== pantalla =====================

    private void actualizar() {
        boolean g = GrabarService.grabando;
        reloj.setText(GrabarService.tiempo(g ? GrabarService.msGrabados : 0));
        if (GrabarService.guardando) {
            estado.setText("Guardando…");
            estado.setTextColor(AMARILLO);
        } else if (g && GrabarService.pausado) {
            estado.setText("En pausa");
            estado.setTextColor(AMARILLO);
        } else if (g) {
            estado.setText("● Grabando el audio del celular");
            estado.setTextColor(ROJO);
        } else {
            estado.setText("Listo para grabar");
            estado.setTextColor(GRIS);
        }

        int ancho = cajaNivel.getWidth();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) barraNivel.getLayoutParams();
        int nuevo = g ? ancho * GrabarService.nivel / 100 : 0;
        if (lp.width != nuevo) {
            lp.width = nuevo;
            barraNivel.setLayoutParams(lp);
        }

        fondo(botonGrabar, g ? Color.rgb(60, 64, 76) : ROJO, dp(32));
        botonGrabar.setText(g ? "■  Parar y guardar" : "●  Grabar");
        botonPausa.setVisibility(g ? View.VISIBLE : View.GONE);
        fondo(botonPausa, Color.rgb(36, 40, 50), dp(24));
        botonPausa.setText(GrabarService.pausado ? "▶  Seguir" : "❚❚  Pausar");

        if (g && !estabaGrabando) aviso.setVisibility(View.GONE);
        if (!g && estabaGrabando || (!g && GrabarService.mensaje != null && aviso.getVisibility() == View.GONE)) {
            String m = GrabarService.mensaje;
            if (m != null) {
                aviso.setText(m);
                aviso.setTextColor(m.startsWith("Guardado") ? VERDE : AMARILLO);
                aviso.setVisibility(View.VISIBLE);
            }
        }
        estabaGrabando = g;

        if (cambiosVistos != GrabarService.cambios) {
            cambiosVistos = GrabarService.cambios;
            cargarLista();
        }
    }

    private void marcarFormato() {
        boolean wav = sp.getBoolean("wav", false);
        circulo(circuloM4a, !wav);
        circulo(circuloWav, wav);
    }

    private View opcion(LinearLayout padre, String titulo, String detalle, final boolean esWav) {
        LinearLayout fila = new LinearLayout(this);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        fila.setPadding(0, dp(10), 0, dp(10));
        View c = new View(this);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(dp(24), dp(24));
        lc.rightMargin = dp(14);
        fila.addView(c, lc);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(texto(titulo, 15, BLANCO));
        t.addView(texto(detalle, 12, GRIS));
        fila.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        fila.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (GrabarService.grabando) {
                    Toast.makeText(MainActivity.this, "Cámbialo cuando no estés grabando", Toast.LENGTH_SHORT).show();
                    return;
                }
                sp.edit().putBoolean("wav", esWav).apply();
                marcarFormato();
            }
        });
        padre.addView(fila);
        return c;
    }

    private void circulo(View c, boolean activo) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (activo) {
            g.setColor(VERDE);
            g.setStroke(dp(2), VERDE);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(2), GRIS);
        }
        c.setBackground(g);
    }

    // ===================== lista de grabaciones =====================

    private static class Item {
        long id;
        String nombre;
        long tam;
        long durMs;
    }

    private List<Item> leerGrabaciones() {
        List<Item> r = new ArrayList<>();
        Uri col = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DURATION};
        try (Cursor c = getContentResolver().query(col, proj,
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{"Music/GrabaAudio%"},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return r;
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0);
                it.nombre = c.getString(1);
                it.tam = c.getLong(2);
                it.durMs = c.getLong(3);
                if (it.durMs <= 0) it.durMs = sp.getLong("dur_" + it.nombre, 0) * 1000;
                r.add(it);
            }
        } catch (RuntimeException e) {
            // nada
        }
        return r;
    }

    private void cargarLista() {
        lista.removeAllViews();
        List<Item> items = leerGrabaciones();
        if (items.isEmpty()) {
            lista.addView(texto("Todavía no hay grabaciones.", 14, GRIS));
            return;
        }
        for (final Item it : items) {
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.VERTICAL);
            fila.setPadding(0, dp(10), 0, dp(10));

            TextView n = texto(it.nombre, 14, BLANCO);
            fila.addView(n);
            String info = String.format(Locale.US, "%s · %.1f MB",
                    it.durMs > 0 ? GrabarService.tiempo(it.durMs) : "--:--", it.tam / 1048576.0);
            fila.addView(texto(info, 12, GRIS));

            LinearLayout bs = new LinearLayout(this);
            bs.setPadding(0, dp(8), 0, 0);
            final Uri u = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), it.id);
            final TextView play = chip(bs, sonandoId == it.id ? "■ Parar" : "▶ Escuchar", VERDE);
            if (sonandoId == it.id) botonSonando = play;
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (sonandoId == it.id) pararReproduccion();
                    else reproducir(it.id, u, play);
                }
            });
            TextView compartir = chip(bs, "↗ Compartir", AZUL);
            compartir.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType(it.nombre.endsWith(".wav") ? "audio/x-wav" : "audio/mp4");
                    s.putExtra(Intent.EXTRA_STREAM, u);
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Compartir audio"));
                }
            });
            TextView borrar = chip(bs, "🗑 Borrar", ROJO);
            borrar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("¿Borrar esta grabación?")
                            .setMessage(it.nombre)
                            .setNegativeButton("No", null)
                            .setPositiveButton("Borrar", (dlg, x) -> borrar(it, u))
                            .show();
                }
            });
            fila.addView(bs);
            lista.addView(fila);

            View linea = new View(this);
            linea.setBackgroundColor(BORDE);
            lista.addView(linea, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void borrar(Item it, Uri u) {
        if (sonandoId == it.id) pararReproduccion();
        try {
            getContentResolver().delete(u, null, null);
            sp.edit().remove("dur_" + it.nombre).apply();
        } catch (RuntimeException e) {
            Toast.makeText(this, "No se pudo borrar. Bórralo desde tu app de Archivos.", Toast.LENGTH_LONG).show();
        }
        cargarLista();
    }

    private void reproducir(long id, Uri u, TextView boton) {
        pararReproduccion();
        try {
            reproductor = new MediaPlayer();
            reproductor.setDataSource(this, u);
            reproductor.setOnCompletionListener(mp -> pararReproduccion());
            reproductor.prepare();
            reproductor.start();
            sonandoId = id;
            botonSonando = boton;
            boton.setText("■ Parar");
        } catch (Exception e) {
            pararReproduccion();
            Toast.makeText(this, "No se pudo reproducir", Toast.LENGTH_SHORT).show();
        }
    }

    private void pararReproduccion() {
        if (reproductor != null) {
            try {
                reproductor.stop();
            } catch (RuntimeException e) {
                // nada
            }
            reproductor.release();
            reproductor = null;
        }
        sonandoId = -1;
        if (botonSonando != null) botonSonando.setText("▶ Escuchar");
        botonSonando = null;
    }

    // ===================== ayudantes de diseño =====================

    private int dp(float v) {
        return Math.round(v * d);
    }

    private TextView texto(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView subtitulo(String s) {
        TextView t = texto(s, 17, BLANCO);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView nota(String s) {
        TextView t = texto(s, 13, GRIS);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private void espacio(LinearLayout p, int alto) {
        p.addView(new View(this), new LinearLayout.LayoutParams(1, dp(alto)));
    }

    private LinearLayout tarjeta(LinearLayout padre) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(TARJETA);
        g.setCornerRadius(dp(18));
        g.setStroke(1, BORDE);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        padre.addView(t, lp);
        return t;
    }

    private void fondo(View v, int color, int radio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radio);
        v.setBackground(g);
    }

    private TextView chip(LinearLayout padre, String s, int color) {
        TextView t = texto(s, 13, color);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.rgb(36, 40, 50));
        g.setCornerRadius(dp(16));
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        padre.addView(t, lp);
        return t;
    }
}
