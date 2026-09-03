package com.doob.mathagent.feishu;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 按后端租户自动创建/复用飞书云空间讲义库文件夹。
 *
 * <p>建库幂等由两层保证：DB 的 tenant_id 唯一键（本服务），以及飞书侧先列父目录按名
 * 匹配再创建（防止历史手工同名文件夹或并发实例各建一份）。父目录取
 * FEISHU_LIBRARY_PARENT_TOKEN（管理员预先共享给机器人的目录）；未配置时落在机器人
 * 我的空间根目录（folder_token 传空即根）。</p>
 */
@Service
public class FeishuTenantLibraryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FeishuTenantLibraryService.class);
    /** 飞书允许重名：冲突时给文件夹名加短后缀重试，保证每次都能定位到确定的一份。 */
    private static final int MAX_NAME_ATTEMPTS = 3;

    private final FeishuTenantLibraryMapper mapper;
    private final FeishuDriveClient drive;
    private final FeishuTenantTokenService tokens;
    private final Environment environment;

    public FeishuTenantLibraryService(
            FeishuTenantLibraryMapper mapper, FeishuDriveClient drive, FeishuTenantTokenService tokens,
            Environment environment) {
        this.mapper = mapper; this.drive = drive; this.tokens = tokens; this.environment = environment;
    }

    /** Result of an ensure pass: the tenant folder token plus whether this call created it. */
    public record TenantLibrary(String tenantId, String folderName, String folderToken, boolean created) {}

    /** Ensures exactly one Feishu folder belongs to this backend tenant and returns its mapping. */
    public TenantLibrary ensureLibrary(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("authenticated tenant is required");
        }
        FeishuTenantLibraryEntity existing = mapper.findByTenant(tenantId);
        if (existing != null) {
            return new TenantLibrary(tenantId, existing.getFolderName(), existing.getRootFolderToken(), false);
        }
        String accessToken = tokens.token();
        String parent = environment.getProperty("FEISHU_LIBRARY_PARENT_TOKEN", "").strip();
        for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS; attempt += 1) {
            String candidate = folderName(tenantId, attempt);
            String reused = drive.listFolderChildren(accessToken, parent).get(candidate);
            if (reused != null && !reused.isBlank()) {
                LOGGER.info("feishu_library_adopted tenantId={} folder={} token={}", tenantId, candidate, reused);
                FeishuTenantLibraryEntity adopted = record(tenantId, candidate, reused);
                return new TenantLibrary(tenantId, candidate, adopted.getRootFolderToken(), true);
            }
            try {
                String createdToken = drive.createFolder(accessToken, candidate, parent);
                FeishuTenantLibraryEntity saved = record(tenantId, candidate, createdToken);
                LOGGER.info("feishu_library_created tenantId={} folder={} token={}", tenantId, candidate, createdToken);
                return new TenantLibrary(tenantId, candidate, saved.getRootFolderToken(), true);
            } catch (DataAccessException race) {
                // 并发首次触发：唯一键让先写者胜出，后写者复用其行，绝不重复建文件夹。
                FeishuTenantLibraryEntity winner = mapper.findByTenant(tenantId);
                if (winner != null) {
                    return new TenantLibrary(tenantId, winner.getFolderName(), winner.getRootFolderToken(), false);
                }
                throw race;
            }
        }
        throw new IllegalStateException("FEISHU_LIBRARY_UNRESOLVED tenant=" + tenantId);
    }

    private FeishuTenantLibraryEntity record(String tenantId, String folderName, String rootToken) {
        if (rootToken == null || rootToken.isBlank()) {
            throw new IllegalStateException("FEISHU_LIBRARY_TOKEN_MISSING");
        }
        FeishuTenantLibraryEntity row = new FeishuTenantLibraryEntity();
        row.setTenantId(tenantId); row.setFolderName(folderName); row.setRootFolderToken(rootToken);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            mapper.insert(row);
            return row;
        } catch (DuplicateKeyException race) {
            FeishuTenantLibraryEntity winner = mapper.findByTenant(tenantId);
            if (winner == null) throw race;
            return winner;
        }
    }

    /** 文件夹名过滤控制字符与路径分隔符；attempt 后缀只在名称冲突时出现。 */
    String folderName(String tenantId, int attempt) {
        return folderName(tenantId, attempt, environment.getProperty("FEISHU_LIBRARY_PREFIX", "mathagent").strip());
    }

    static String folderName(String tenantId, int attempt, String prefix) {
        String sanitized = tenantId.strip().replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|\\[\\]]", "_");
        return prefix + "-" + sanitized + (attempt <= 1 ? "" : "-" + attempt);
    }
}
