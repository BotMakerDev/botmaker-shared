package com.botmaker.shared.ocr;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * OpenCV image preprocessing that turns a raw screen capture into something Tesseract reads well. Game
 * fonts are Tesseract's weak spot; this pipeline — grayscale, upscale, binarize, optional invert — is what
 * makes on-screen OCR viable. All steps are driven by {@link OcrOptions}.
 *
 * <p>Only {@link OcrOptions#upscale()} changes geometry; the resulting image's coordinates are
 * {@code upscale ×} the source's, and {@link OcrEngine} divides bounding boxes back down.
 */
public final class OcrPreprocessor {

    private OcrPreprocessor() {}

    /** Applies the {@code opts} pipeline to {@code src} and returns a new image ready for Tesseract. */
    public static BufferedImage process(BufferedImage src, OcrOptions opts) {
        OcrNative.ensureOpenCvLoaded();
        Mat mat = bufferedImageToMat(src);
        try {
            if (opts.grayscale() || opts.binarize() != OcrOptions.BinarizeMode.NONE) {
                if (mat.channels() == 3) Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY);
            }

            double scale = opts.upscale();
            if (scale > 0 && Math.abs(scale - 1.0) > 1e-3) {
                int w = Math.max(1, (int) Math.round(mat.cols() * scale));
                int h = Math.max(1, (int) Math.round(mat.rows() * scale));
                Imgproc.resize(mat, mat, new Size(w, h), 0, 0,
                        scale < 1.0 ? Imgproc.INTER_AREA : Imgproc.INTER_CUBIC);
            }

            switch (opts.binarize()) {
                case OTSU -> Imgproc.threshold(mat, mat, 0, 255,
                        Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
                case ADAPTIVE -> Imgproc.adaptiveThreshold(mat, mat, 255,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 10);
                case NONE -> { /* leave grayscale (or original) as-is */ }
            }

            if (opts.invert()) {
                Core.bitwise_not(mat, mat);
            }

            return matToBufferedImage(mat);
        } finally {
            mat.release();
        }
    }

    // --- BufferedImage <-> Mat bridge (re-implemented here so shared does NOT depend on the SDK) -------

    /** Copies a {@link BufferedImage} into a 3-channel BGR {@link Mat}. */
    static Mat bufferedImageToMat(BufferedImage image) {
        BufferedImage bgr = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        bgr.getGraphics().drawImage(image, 0, 0, null);

        Mat mat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC3);
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        mat.put(0, 0, pixels);
        return mat;
    }

    /** Copies a single-channel (gray/binary) or 3-channel BGR {@link Mat} back into a {@link BufferedImage}. */
    static BufferedImage matToBufferedImage(Mat mat) {
        int type = mat.channels() == 1 ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR;
        BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), type);
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat.get(0, 0, data);
        return image;
    }
}
