package com.doob.mathagent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Real Feishu drive writer used by the per-tenant library and batch upload path. */
@Service
public class HttpFeishuDriveClient implements FeishuDriveClient {
    private static final String BASE = "https://open.feishu.cn/open-apis";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String createFolder(String accessToken, String name, String parentToken) {
        try {
            // type=folder 是 drive/v1/files 创建文件夹的必需字段；缺省会创建空文件而不是目录。
            java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("name", name);
            fields.put("type", "folder");
            if (parentToken != null && !parentToken.isBlank()) {
                fields.put("folder_token", parentToken);
            }
            String body = mapper.writeValueAsString(fields);
            JsonNode root = post(accessToken, "/drive/v1/files",
                    java.util.Map.of(), HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            requireOk(root, "createFolder");
            return root.path("data").path("file_token").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("FEISHU_FOLDER_CREATE_FAILED", exception);
        }
    }

    @Override
    public java.util.Map<String, String> listFolderChildren(String accessToken, String folderToken) {
        java.util.Map<String, String> children = new java.util.LinkedHashMap<>();
        String pageToken = "";
        try {
            do {
                String query = "page_size=200&order_by=EditedTime&direction=DESC";
                if (folderToken != null && !folderToken.isBlank()) {
                    query += "&folder_token=" + URLEncoder.encode(folderToken, StandardCharsets.UTF_8);
                }
                if (pageToken != null && !pageToken.isBlank()) {
                    query += "&page_token=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
                }
                JsonNode root = get(accessToken, "/drive/v1/files?" + query);
                requireOk(root, "listFolder");
                for (JsonNode item : root.path("data").path("files")) {
                    children.put(item.path("name").asText(""), item.path("file_token").asText(item.path("token").asText("")));
                }
                pageToken = root.path("data").path("page_token").asText("");
            } while (pageToken != null && !pageToken.isBlank());
        } catch (Exception exception) {
            throw new IllegalStateException("FEISHU_FOLDER_LIST_FAILED", exception);
        }
        return children;
    }

    @Override
    public String uploadFile(String accessToken, String parentFolderToken, String fileName, byte[] bytes) {
        try {
            String boundary = "----mathagent" + System.nanoTime();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + "/drive/v1/files/upload_all"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, parentFolderToken, fileName, bytes)));
            JsonNode root = mapper.readTree(http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
            requireOk(root, "uploadFile");
            return root.path("data").path("file_token").asText();
        } catch (Exception exception) {
            throw new IllegalStateException("FEISHU_FILE_UPLOAD_FAILED", exception);
        }
    }

    /** upload_all form contract: parent_type/parent_node/file_name/size plus the binary part. */
    private static byte[] multipart(String boundary, String folderToken, String fileName, byte[] content) {
        StringBuilder form = new StringBuilder();
        appendField(form, boundary, "parent_type", "explorer");
        appendField(form, boundary, "parent_node", folderToken);
        appendField(form, boundary, "file_name", fileName);
        appendField(form, boundary, "size", String.valueOf(content.length));
        form.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
                .append("Content-Type: application/octet-stream\r\n\r\n");
        byte[] prefix = form.toString().getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + content.length + suffix.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(content, 0, out, prefix.length, content.length);
        System.arraycopy(suffix, 0, out, prefix.length + content.length, suffix.length);
        return out;
    }

    private static void appendField(StringBuilder form, String boundary, String key, String value) {
        form.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value == null ? "" : value).append("\r\n");
    }

    private JsonNode post(String accessToken, String path, java.util.Map<String, String> query,
            HttpRequest.BodyPublisher publisher) throws Exception {
        StringBuilder target = new StringBuilder(BASE + path);
        if (!query.isEmpty()) {
            target.append('?');
            query.forEach((key, value) -> target.append(key).append('=').append(value).append('&'));
            target.setLength(target.length() - 1);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.toString()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(publisher).build();
        return mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
    }

    private JsonNode get(String accessToken, String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + pathAndQuery))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET().build();
        return mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
    }

    /** 飞书业务码非 0 一律视为失败：权限/限流错误不能伪装成"建库成功"。 */
    private static void requireOk(JsonNode root, String operation) {
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("feishu " + operation + " rejected code=" + code
                    + " msg=" + root.path("msg").asText(""));
        }
    }
}
