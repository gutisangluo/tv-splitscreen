package com.splitscreen.tv;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 嵌入式 HTTP 文件服务器
 * - POST /upload → 接收文件上传，返回可访问的 URL
 * - GET /media/{filename} → 提供已上传的媒体文件
 */
public class FileServer extends NanoHTTPD {

    private static final String TAG = "FileServer";
    private static final int DEFAULT_PORT = 9528;  // 与 WS 端口区分开

    private final File mediaDir;

    public FileServer(Context context) {
        super(DEFAULT_PORT);
        // 媒体文件存储路径: app 内部存储
        mediaDir = new File(context.getFilesDir(), "media");
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        Log.d(TAG, method + " " + uri);

        // CORS headers（允许手机控制端跨域访问）
        Response resp;

        // OPTIONS → CORS preflight
        if (method == Method.OPTIONS) {
            resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
            addCorsHeaders(resp);
            return resp;
        }

        // POST /upload → 接收文件上传
        if ("/upload".equals(uri) && method == Method.POST) {
            return handleUpload(session);
        }

        // GET /media/{filename} → 提供文件
        if (uri.startsWith("/media/") && method == Method.GET) {
            return handleDownload(uri);
        }

        // 404
        resp = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        addCorsHeaders(resp);
        return resp;
    }

    private Response handleUpload(IHTTPSession session) {
        try {
            // 解析 multipart/form-data 上传
            Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);

            // 找上传的文件（NanoHTTPD 把文件存到临时目录）
            String filePath = null;
            String fileName = "upload";
            for (Map.Entry<String, String> entry : files.entrySet()) {
                // NanoHTTPD 把上传文件保存到临时文件，key 是 form field name
                String tmpPath = entry.getValue();
                if (tmpPath != null && !tmpPath.isEmpty()) {
                    filePath = tmpPath;
                    fileName = entry.getKey();
                    break;
                }
            }

            // 也尝试从 query 参数获取文件名
            String nameParam = session.getParameters().get("name") != null
                    ? session.getParameters().get("name").get(0) : null;
            if (nameParam != null && !nameParam.isEmpty()) {
                fileName = nameParam;
            }

            if (filePath == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                        "application/json",
                        "{\"error\":\"没有找到上传文件\"}");
            }

            // 读取原始文件名
            // 优先：JS 上传时以文件名作为 form field name
            String originalName = fileName;
            // 后备：从 Content-Disposition 解析原始文件名
            // 注意：NanoHTTPD 的 multipart 部分 Content-Disposition 头
            // 可能在 session.getHeaders() 中，也可能在 parseBody 的临时文件元数据中
            for (Map.Entry<String, String> hdr : session.getHeaders().entrySet()) {
                String key = hdr.getKey().toLowerCase();
                if (key.contains("content-disposition") || key.equals("content-disposition")) {
                    String val = hdr.getValue();
                    int idx = val.indexOf("filename=\"");
                    if (idx > 0) {
                        int end = val.indexOf("\"", idx + 10);
                        if (end > idx + 10) {
                            originalName = val.substring(idx + 10, end);
                        }
                    }
                }
            }
            // 如果还是没拿到扩展名，尝试从临时文件路径推断
            if (!originalName.contains(".")) {
                String tmpLower = filePath.toLowerCase();
                for (String knownExt : new String[]{".mp4",".avi",".mkv",".mov",".jpg",".jpeg",".png",".gif",".webp"}) {
                    if (tmpLower.contains(knownExt)) {
                        originalName = "file" + knownExt;
                        break;
                    }
                }
            }

            // 生成唯一文件名
            String ext = "";
            int dotIdx = originalName.lastIndexOf('.');
            if (dotIdx > 0) {
                ext = originalName.substring(dotIdx).toLowerCase();
            }
            String uniqueName = System.currentTimeMillis() + "_" + (int)(Math.random() * 10000) + ext;
            File targetFile = new File(mediaDir, uniqueName);

            // 从临时文件复制到目标
            File tmpFile = new File(filePath);
            if (tmpFile.exists()) {
                // 重命名比复制快
                tmpFile.renameTo(targetFile);
            } else {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "application/json",
                        "{\"error\":\"文件保存失败\"}");
            }

            long fileSize = targetFile.length();
            String mediaUrl = "/media/" + uniqueName;

            Log.d(TAG, "文件上传成功: " + uniqueName + " (" + fileSize + " bytes)");

            // 返回 JSON，包含可访问的 URL
            String json = String.format(
                    "{\"url\":\"%s\",\"name\":\"%s\",\"size\":%d}",
                    mediaUrl, originalName, fileSize
            );

            Response resp = newFixedLengthResponse(Response.Status.OK,
                    "application/json", json);
            addCorsHeaders(resp);
            return resp;

        } catch (Exception e) {
            Log.e(TAG, "上传失败", e);
            Response resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
            addCorsHeaders(resp);
            return resp;
        }
    }

    private Response handleDownload(String uri) {
        String fileName = uri.substring("/media/".length());

        // 安全检查：防止路径穿越
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN,
                    "text/plain", "Forbidden");
        }

        File file = new File(mediaDir, fileName);
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "text/plain", "File Not Found");
        }

        // 根据扩展名判断 MIME 类型
        String mime = getMimeType(fileName);

        try {
            Response resp = newChunkedResponse(Response.Status.OK, mime, new java.io.FileInputStream(file));
            addCorsHeaders(resp);
            return resp;
        } catch (java.io.FileNotFoundException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File Not Found");
        }
    }

    private String getMimeType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".avi")) return "video/avi";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return "application/octet-stream";
    }

    private void addCorsHeaders(Response resp) {
        resp.addHeader("Access-Control-Allow-Origin", "*");
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    /**
     * 获取本机 HTTP 服务器地址
     */
    public String getLocalUrl() {
        return "http://" + getHostname() + ":" + getListeningPort();
    }

    /**
     * 获取媒体文件的完整 URL
     */
    public String getMediaUrl(String fileName) {
        return getLocalUrl() + "/media/" + fileName;
    }
}
