/*
 * Provenance: ORIGINAL. Times JPEG 2000 decoding of a list of files in one JVM, single-threaded.
 *   java -cp icepdf-jpx.jar[:jai-imageio-*.jar] JpxBench.java <ours|jj2000> <warmupFiles> <listFile>
 * Prints per file: path, ok|fail|timeout, ms, megapixels (width*height of the image). The first
 * <warmupFiles> files of the list are decoded once untimed first, to warm the JIT.
 */
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;

public class JpxBench {
    interface Decoder { long decode(byte[] b) throws Exception; }

    static long ours(byte[] b) throws Exception {
        org.icepdf.jpx.Jpeg2000Image img = org.icepdf.jpx.Jpeg2000Decoder.decodeImage(b, 0, b.length, 0);
        return (long) img.components[0].width * img.components[0].height;
    }

    static long jj2000(byte[] b) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(b))) {
            Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("jpeg2000");
            ImageReader r = null;
            while (it.hasNext()) {
                ImageReader x = it.next();
                if (x.getClass().getName().contains("jaiimageio")) r = x;
            }
            r.setInput(in, true, true);
            try {
                java.awt.image.Raster ras = r.readRaster(0, null);
                return (long) ras.getWidth() * ras.getHeight();
            } finally {
                r.dispose();
            }
        }
    }

    public static void main(String[] a) throws Exception {
        Decoder d = a[0].equals("ours") ? JpxBench::ours : JpxBench::jj2000;
        int warm = Integer.parseInt(a[1]);
        List<String> files = Files.readAllLines(Paths.get(a[2]));
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        for (int i = 0; i < Math.min(warm, files.size()); i++) {
            byte[] b = Files.readAllBytes(Paths.get(files.get(i)));
            try { ex.submit(() -> d.decode(b)).get(20, TimeUnit.SECONDS); } catch (Exception e) { ex.shutdownNow(); ex = fresh(); }
        }
        for (String f : files) {
            byte[] b = Files.readAllBytes(Paths.get(f));
            long t0 = System.nanoTime();
            String status;
            long px = 0;
            try {
                px = ex.submit(() -> d.decode(b)).get(20, TimeUnit.SECONDS);
                status = "ok";
            } catch (TimeoutException e) {
                status = "timeout";
                ex.shutdownNow();
                ex = fresh();
            } catch (ExecutionException e) {
                status = "fail";
            }
            System.out.printf("%s\t%s\t%.3f\t%.4f%n", f, status, (System.nanoTime() - t0) / 1e6, px / 1e6);
        }
    }

    static ExecutorService fresh() {
        return Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
    }
}
