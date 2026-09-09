package com.shmuelzon.HomeAssistantFloorPlan;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.imageio.ImageIO;

// Re-renders a floor plan image photorealistically with an AI image model
// (Google Gemini image editing). The prompt pins down everything that must not
// change (layout, wall colors, floor materials, furniture, background) so the
// AI pass only adds realism without altering the actual floor plan. Crucially it
// forbids the model from switching lights on: base/night images are rendered in
// specific light states and the per-light renders are layered on top in Home
// Assistant, so any extra glow the AI invents would make lights look permanently
// on. Adding tasteful decoration (set tables, cushions, ...) is allowed.
public class AiImageRenderer {
    private static final String API_URL_FORMAT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final int CONNECT_TIMEOUT_MS = 30 * 1000;
    private static final int READ_TIMEOUT_MS = 5 * 60 * 1000;
    // The standard prompt shown (and editable) in the GUI. Used verbatim for the
    // first image of a run (base_day), which establishes the decoration every
    // later image reproduces. Geometry and existing materials must be preserved
    // exactly; only small props may be added.
    public static final String DEFAULT_PROMPT =
        "Re-render this top-down 3D dollhouse-style floor plan photorealistically, keeping it an exact structural copy of the input. " +
        "Every wall, room, window, door and piece of furniture must keep the exact same position, size, scale, proportions and outline as in the input - " +
        "never enlarge, shrink, stretch, move, rotate or reshape anything. The dining table, sofas, beds, counters and cabinets must keep their exact footprint and dimensions. " +
        "Keep all wall colors and floor materials, and keep existing rugs, carpets, curtains and textiles in their original shape, size, color and pattern - do not restyle, recolor or replace them. " +
        "CRITICAL: preserve the exact lighting of the input. Do NOT switch on any lamp or ceiling light and do not add glowing bulbs, light cones, lens flare, bloom or bright light pools; " +
        "render every lamp and ceiling light as an unlit, switched-off fixture and keep the flat, even daytime brightness unchanged. " +
        "You may only add small props that rest on top of existing surfaces - plates and cutlery on a table, a couple of cushions on a sofa, a few books - " +
        "and these props must stay small and must not change the size, shape, position or color of the furniture beneath them. " +
        "Otherwise only increase realism: natural materials, soft shadows, subtle reflections and fine texture detail. " +
        "Keep the background outside the floor plan exactly as it is, including its solid color.";

    // Fixed technical preamble prepended to the user prompt for every later image.
    // It explains the two-image setup (reference + raw render) so the model copies
    // the reference's decoration but keeps the raw render's own geometry and
    // lighting, keeping day/night/light renders pixel-consistent for the Home
    // Assistant overlay stack. "the input" in the user prompt then means the
    // SECOND image.
    private static final String REFERENCE_PREAMBLE =
        "You are given two top-down images of the exact same 3D dollhouse-style floor plan. " +
        "The FIRST image is a finished, photorealistic style reference. The SECOND image is a raw render of the same floor plan that you must re-render. " +
        "Reproduce the SECOND image, copying only the decoration and finish from the FIRST image - the same small props, cushions, table settings, rugs, plants and colors - " +
        "while keeping the geometry, furniture footprint, walls, layout, lighting and brightness of the SECOND image. " +
        "If the SECOND image is dark, the result must stay just as dark. Treat \"the input\" in the following instructions as the SECOND image. Instructions: ";

    private String apiKey;
    private String model;
    private String prompt;

    public AiImageRenderer(String apiKey, String model, String prompt) {
        this.apiKey = apiKey;
        this.model = model;
        this.prompt = prompt != null && !prompt.trim().isEmpty() ? prompt : DEFAULT_PROMPT;
    }

    public BufferedImage enhance(BufferedImage image, BufferedImage reference) throws IOException, InterruptedException {
        byte[] response = post(buildRequestBody(image, reference));
        BufferedImage enhanced = decodeImageFromResponse(new String(response, StandardCharsets.UTF_8));
        if (Thread.interrupted())
            throw new InterruptedException();
        // The model does not guarantee the exact input resolution; scale back so
        // the enhanced image stays aligned with the stamp and crop area.
        return scaleToMatch(enhanced, image.getWidth(), image.getHeight());
    }

    private byte[] buildRequestBody(BufferedImage image, BufferedImage reference) throws IOException {
        String encodedImage = encodePng(image);
        // With a reference the user prompt is prefixed with the two-image preamble.
        String promptText = reference != null ? REFERENCE_PREAMBLE + prompt : prompt;
        StringBuilder parts = new StringBuilder();
        parts.append("{\"text\":\"").append(jsonEscape(promptText)).append("\"}");
        // The reference part comes first so it matches the "FIRST image" wording.
        if (reference != null) {
            parts.append(",{\"inline_data\":{\"mime_type\":\"image/png\",\"data\":\"")
                 .append(encodePng(reference)).append("\"}}");
        }
        parts.append(",{\"inline_data\":{\"mime_type\":\"image/png\",\"data\":\"")
             .append(encodedImage).append("\"}}");
        String json =
            "{\"contents\":[{\"parts\":[" + parts + "]}]," +
            "\"generationConfig\":{\"responseModalities\":[\"IMAGE\"]}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private String encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", pngBytes);
        return Base64.getEncoder().encodeToString(pngBytes.toByteArray());
    }

    // Escapes a string for embedding inside a JSON string literal. The prompt is
    // user-editable, so it may contain quotes, backslashes or newlines that would
    // otherwise break the hand-built request body.
    private String jsonEscape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int)c));
                    else
                        sb.append(c);
            }
        }
        return sb.toString();
    }

    private byte[] post(byte[] body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(String.format(API_URL_FORMAT, model)).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("x-goog-api-key", apiKey);
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.close();

            int status = connection.getResponseCode();
            InputStream in = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            byte[] response = in != null ? readAll(in) : new byte[0];
            if (status < 200 || status >= 300)
                throw new IOException("AI rendering request failed (HTTP " + status + "): "
                    + extractErrorMessage(new String(response, StandardCharsets.UTF_8)));
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int read;
        while ((read = in.read(chunk)) != -1)
            buffer.write(chunk, 0, read);
        in.close();
        return buffer.toByteArray();
    }

    private BufferedImage decodeImageFromResponse(String response) throws IOException {
        // The response is JSON with the generated image under
        // candidates[].content.parts[].inlineData.data as base64. Locating the
        // "data" string after the "inlineData" marker avoids needing a JSON
        // library, which the plugin environment does not provide.
        int inlineDataIndex = response.indexOf("\"inlineData\"");
        if (inlineDataIndex < 0)
            inlineDataIndex = response.indexOf("\"inline_data\"");
        if (inlineDataIndex < 0)
            throw new IOException("AI rendering response contains no image: " + snippet(response));
        String encodedImage = extractJsonString(response, "\"data\"", inlineDataIndex);
        if (encodedImage == null)
            throw new IOException("AI rendering response contains no image data: " + snippet(response));
        byte[] imageBytes = Base64.getMimeDecoder().decode(encodedImage.replace("\\n", "").replace("\\r", ""));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null)
            throw new IOException("AI rendering returned an unreadable image");
        return image;
    }

    private String extractErrorMessage(String response) {
        String message = extractJsonString(response, "\"message\"", 0);
        return message != null ? message : snippet(response);
    }

    private String extractJsonString(String json, String key, int fromIndex) {
        int keyIndex = json.indexOf(key, fromIndex);
        if (keyIndex < 0)
            return null;
        int start = json.indexOf('"', json.indexOf(':', keyIndex + key.length()) + 1);
        if (start < 0)
            return null;
        int end = start + 1;
        while (end < json.length() && (json.charAt(end) != '"' || json.charAt(end - 1) == '\\'))
            end++;
        if (end >= json.length())
            return null;
        return json.substring(start + 1, end);
    }

    private String snippet(String text) {
        String trimmed = text.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    private BufferedImage scaleToMatch(BufferedImage image, int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height)
            return image;
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(image, 0, 0, width, height, null);
        g2d.dispose();
        return scaled;
    }
};
