package com.chivits.grabaaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Graba el audio INTERNO del celular (lo que suena en videos, música, juegos), sin micrófono.
 * Usa la captura de reproducción de Android 10+ (AudioPlaybackCapture).
 */
public class GrabarService extends Service {

    static final String PARAR = "parar";
    static final String PAUSAR = "pausar";

    static final int TASA = 44100;
    static final int CANALES = 2;

    // estado que lee la pantalla principal
    static volatile boolean grabando = false;
    static volatile boolean pausado = false;
    static volatile boolean guardando = false;
    static volatile long msGrabados = 0;     // tiempo real grabado (sin pausas)
    static volatile int nivel = 0;           // 0..100, volumen actual
    static volatile String mensaje = null;   // aviso para mostrar al terminar
    static volatile int cambios = 0;         // sube cada vez que se guarda un archivo

    private MediaProjection proyeccion;
    private AudioRecord rec;
    private Thread hilo;
    private volatile boolean seguir = false;
    private boolean wav = false;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String acc = intent != null ? intent.getAction() : null;
        if (PARAR.equals(acc)) {
            parar();
            return START_NOT_STICKY;
        }
        if (PAUSAR.equals(acc)) {
            pausado = !pausado;
            avisar();
            return START_NOT_STICKY;
        }
        if (grabando || intent == null) return START_NOT_STICKY;

        // 1) primero el aviso fijo (Android lo exige antes de usar la captura)
        crearCanal();
        try {
            startForeground(1, notificacion(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (RuntimeException e) {
            mensaje = "No se pudo iniciar la grabación.";
            stopSelf();
            return START_NOT_STICKY;
        }

        int codigo = intent.getIntExtra("codigo", 0);
        Intent datos = intent.getParcelableExtra("datos");
        wav = intent.getBooleanExtra("wav", false);
        if (datos == null) {
            terminarServicio();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            proyeccion = mpm.getMediaProjection(codigo, datos);
            proyeccion.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    parar(); // si el sistema corta la captura, se guarda lo grabado
                }
            }, h);

            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(proyeccion)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(TASA)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min * 4, 16384))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            rec.startRecording();
        } catch (SecurityException e) {
            mensaje = "Falta el permiso de grabar audio.";
            terminarServicio();
            return START_NOT_STICKY;
        } catch (RuntimeException e) {
            mensaje = "Este celular no dejó capturar el audio.";
            terminarServicio();
            return START_NOT_STICKY;
        }

        grabando = true;
        pausado = false;
        msGrabados = 0;
        nivel = 0;
        mensaje = null;
        seguir = true;
        hilo = new Thread(new Runnable() {
            @Override
            public void run() {
                grabar();
            }
        }, "graba-audio");
        hilo.start();
        avisar();
        return START_NOT_STICKY;
    }

    // ===================== grabación =====================

    private void grabar() {
        File tmp = new File(getCacheDir(), wav ? "grabando.wav" : "grabando.m4a");
        if (tmp.exists()) tmp.delete();
        boolean ok;
        Escritor esc = null;
        int picoTotal = 0;
        long bytes = 0;
        try {
            esc = wav ? new EscritorWav(tmp) : new EscritorAac(tmp);
            byte[] buf = new byte[8192];
            long ultimoAviso = 0;
            while (seguir) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                int pico = pico(buf, n);
                if (pausado) {
                    nivel = 0;
                    continue;
                }
                nivel = Math.min(100, pico * 100 / 20000);
                if (pico > picoTotal) picoTotal = pico;
                esc.escribir(buf, n);
                bytes += n;
                msGrabados = bytes * 1000L / (TASA * CANALES * 2);
                long t = System.currentTimeMillis();
                if (t - ultimoAviso > 1000) {
                    ultimoAviso = t;
                    avisar();
                }
            }
            guardando = true;
            ok = esc.cerrar() && bytes > 0;
            esc = null;
        } catch (Exception e) {
            ok = false;
        } finally {
            if (esc != null) {
                try {
                    esc.cerrar();
                } catch (Exception ignorar) {
                    // nada
                }
            }
        }

        if (!ok) {
            mensaje = bytes == 0 ? "No se grabó nada." : "Hubo un error al guardar.";
        } else {
            String nombre = guardarEnMusica(tmp);
            if (nombre == null) {
                mensaje = "No se pudo guardar en Música.";
            } else {
                getSharedPreferences("datos", MODE_PRIVATE).edit()
                        .putLong("dur_" + nombre, msGrabados / 1000).apply();
                if (picoTotal < 60) {
                    mensaje = "Se guardó, pero salió en silencio. Esa app no deja grabar su audio "
                            + "(pasa con Netflix, Spotify y otras protegidas).";
                } else {
                    mensaje = "Guardado: " + nombre + "\nEstá en la carpeta Música > GrabaAudio.";
                }
                cambios++;
            }
        }
        tmp.delete();
        guardando = false;
        h.post(new Runnable() {
            @Override
            public void run() {
                terminarServicio();
            }
        });
    }

    private static int pico(byte[] b, int n) {
        int max = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            if (v < 0) v = -v;
            if (v > max) max = v;
        }
        return max;
    }

    /** Copia el archivo a Música/GrabaAudio para que se vea en cualquier reproductor. */
    private String guardarEnMusica(File f) {
        String fecha = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String nombre = "Audio_" + fecha + (wav ? ".wav" : ".m4a");
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, wav ? "audio/x-wav" : "audio/mp4");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/GrabaAudio");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = null;
        try {
            u = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
            if (u == null) return null;
            try (InputStream in = new FileInputStream(f); OutputStream out = cr.openOutputStream(u)) {
                if (out == null) throw new IllegalStateException();
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            ContentValues fin = new ContentValues();
            fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, fin, null, null);
            return nombre;
        } catch (Exception e) {
            if (u != null) {
                try {
                    cr.delete(u, null, null);
                } catch (Exception ignorar) {
                    // nada
                }
            }
            return null;
        }
    }

    // ===================== parar =====================

    private void parar() {
        if (!grabando) {
            terminarServicio();
            return;
        }
        seguir = false; // el hilo termina, guarda y llama a terminarServicio()
        avisar();
    }

    private void terminarServicio() {
        seguir = false;
        grabando = false;
        pausado = false;
        nivel = 0;
        try {
            if (rec != null) {
                rec.stop();
                rec.release();
            }
        } catch (RuntimeException e) {
            // nada
        }
        rec = null;
        try {
            if (proyeccion != null) proyeccion.stop();
        } catch (RuntimeException e) {
            // nada
        }
        proyeccion = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        seguir = false;
        super.onDestroy();
    }

    // ===================== notificación =====================

    private void crearCanal() {
        NotificationChannel c = new NotificationChannel("grabando", "Grabación de audio",
                NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }

    private void avisar() {
        if (!grabando) return;
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notificacion());
        } catch (RuntimeException e) {
            // nada
        }
    }

    private Notification notificacion() {
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), fl);
        PendingIntent pp = PendingIntent.getService(this, 1,
                new Intent(this, GrabarService.class).setAction(PAUSAR), fl);
        PendingIntent st = PendingIntent.getService(this, 2,
                new Intent(this, GrabarService.class).setAction(PARAR), fl);
        String txt;
        if (guardando || (grabando && !seguir)) txt = "Guardando…";
        else if (pausado) txt = "En pausa · " + tiempo(msGrabados);
        else txt = "Grabando el audio del celular · " + tiempo(msGrabados);
        return new Notification.Builder(this, "grabando")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Graba Audio")
                .setContentText(txt)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(abrir)
                .addAction(new Notification.Action.Builder(null, pausado ? "Seguir" : "Pausar", pp).build())
                .addAction(new Notification.Action.Builder(null, "Parar y guardar", st).build())
                .build();
    }

    static String tiempo(long ms) {
        long s = ms / 1000;
        long hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        if (hh > 0) return String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss);
        return String.format(Locale.US, "%02d:%02d", mm, ss);
    }

    // ===================== escritores de archivo =====================

    interface Escritor {
        void escribir(byte[] b, int n) throws Exception;

        boolean cerrar() throws Exception;
    }

    /** WAV sin comprimir: calidad máxima, pesa ~10 MB por minuto. */
    static class EscritorWav implements Escritor {
        private final RandomAccessFile f;
        private long datos = 0;

        EscritorWav(File archivo) throws Exception {
            f = new RandomAccessFile(archivo, "rw");
            f.setLength(0);
            f.write(new byte[44]);
        }

        @Override
        public void escribir(byte[] b, int n) throws Exception {
            f.write(b, 0, n);
            datos += n;
        }

        @Override
        public boolean cerrar() throws Exception {
            f.seek(0);
            f.write(cabecera(datos));
            f.close();
            return datos > 0;
        }

        static byte[] cabecera(long datos) {
            ByteBuffer h = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int bloque = CANALES * 2;
            h.put(new byte[]{'R', 'I', 'F', 'F'});
            h.putInt((int) (36 + datos));
            h.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            h.putInt(16);
            h.putShort((short) 1);
            h.putShort((short) CANALES);
            h.putInt(TASA);
            h.putInt(TASA * bloque);
            h.putShort((short) bloque);
            h.putShort((short) 16);
            h.put(new byte[]{'d', 'a', 't', 'a'});
            h.putInt((int) datos);
            return h.array();
        }
    }

    /** M4A (AAC 192 kbps): suena casi igual al original y pesa ~1,4 MB por minuto. */
    static class EscritorAac implements Escritor {
        private final MediaCodec codec;
        private final MediaMuxer muxer;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int pista = -1;
        private boolean iniciado = false;
        private long total = 0;

        EscritorAac(File archivo) throws Exception {
            MediaFormat mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, TASA, CANALES);
            mf.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            mf.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
            mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(archivo.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        private long pts() {
            return total * 1000000L / (TASA * CANALES * 2);
        }

        @Override
        public void escribir(byte[] b, int n) throws Exception {
            int off = 0;
            while (off < n) {
                int idx = codec.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(idx);
                    ib.clear();
                    int c = Math.min(ib.remaining(), n - off);
                    c -= c % (CANALES * 2);
                    if (c <= 0) c = Math.min(ib.remaining(), n - off);
                    ib.put(b, off, c);
                    codec.queueInputBuffer(idx, 0, c, pts(), 0);
                    total += c;
                    off += c;
                }
                vaciar(false);
            }
        }

        private void vaciar(boolean fin) {
            int esperas = 0;
            while (true) {
                int o = codec.dequeueOutputBuffer(info, fin ? 10000 : 0);
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!fin || ++esperas > 300) return;
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!iniciado) {
                        pista = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        iniciado = true;
                    }
                } else if (o >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(o);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && iniciado && ob != null) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        muxer.writeSampleData(pista, ob, info);
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                }
            }
        }

        @Override
        public boolean cerrar() throws Exception {
            try {
                int idx = -1;
                for (int i = 0; i < 100 && idx < 0; i++) {
                    idx = codec.dequeueInputBuffer(10000);
                    if (idx < 0) vaciar(false);
                }
                if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, pts(), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                vaciar(true);
            } finally {
                try {
                    codec.stop();
                } catch (RuntimeException e) {
                    // nada
                }
                codec.release();
            }
            if (!iniciado) {
                muxer.release();
                return false;
            }
            try {
                muxer.stop();
            } finally {
                muxer.release();
            }
            return true;
        }
    }
}
