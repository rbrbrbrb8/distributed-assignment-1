package com.distributedsystems.worker.download;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import javax.imageio.ImageIO;

/**
 * Downloads an image from a URL into memory.
 *
 * <p>Not final and with an overridable {@link #download(String)} so tests can replace it with a stub.
 */
public class ImageDownloader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param url the image URL
     * @return the decoded image
     * @throws IOException if the URL is invalid, the server does not answer with 2xx,
     *                     or the content is not an image format Java can decode
     */
    public BufferedImage download(String url) throws IOException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "ocr-worker/1.0")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url, e);
        }

        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status);
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
        if (image == null) {
            throw new IOException("Unsupported image format");
        }
        return image;
    }
}
