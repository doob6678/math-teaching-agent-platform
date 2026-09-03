package com.doob.mathagent.feishu;

/**
 * 飞书云空间（drive）写边界：建租户文件夹 + 批量上传讲义文件。
 *
 * <p>接口化是为了让库/上传服务可以在没有网络凭据的单元环境里被完整测试；真实实现
 * HttpFeishuDriveClient 使用 tenant bot token（见 FeishuTenantTokenService）。</p>
 */
public interface FeishuDriveClient {

    /** Creates (or resolves) one folder under the given parent token and returns its file token. */
    String createFolder(String accessToken, String name, String parentToken);

    /** Maps child names to file tokens under one folder (empty when the folder is new or parent is the bot root). */
    java.util.Map<String, String> listFolderChildren(String accessToken, String folderToken);

    /** Uploads one binary file into the folder and returns its file token. */
    String uploadFile(String accessToken, String parentFolderToken, String fileName, byte[] bytes);
}
