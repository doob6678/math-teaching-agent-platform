package com.doob.mathagent.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 按租户建库的幂等合同：DB 映射优先，其次同名复用，最后才创建。 */
class FeishuTenantLibraryServiceTest {

    private static final class FakeDrive implements FeishuDriveClient {
        final Map<String, String> children = new LinkedHashMap<>();
        final List<String> created = new ArrayList<>();
        final Map<String, String> tokens = new HashMap<>();

        @Override
        public String createFolder(String accessToken, String name, String parentToken) {
            created.add(name);
            String token = "fld-" + created.size();
            tokens.put(name, token);
            children.put(name, token);
            return token;
        }

        @Override
        public Map<String, String> listFolderChildren(String accessToken, String folderToken) {
            return children;
        }

        @Override
        public String uploadFile(String accessToken, String parentFolderToken, String fileName, byte[] bytes) {
            throw new UnsupportedOperationException();
        }
    }

    /** MyBatis-Plus mapper 的内存替身：只实现本服务用到的三个方法。 */
    private static FeishuTenantLibraryMapper inMemoryMapper(Map<String, FeishuTenantLibraryEntity> rows) {
        AtomicLong ids = new AtomicLong();
        return (FeishuTenantLibraryMapper) Proxy.newProxyInstance(
                FeishuTenantLibraryMapper.class.getClassLoader(),
                new Class<?>[]{FeishuTenantLibraryMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByTenant" -> rows.get((String) args[0]);
                    case "insert" -> {
                        FeishuTenantLibraryEntity entity = (FeishuTenantLibraryEntity) args[0];
                        entity.setId(ids.incrementAndGet());
                        rows.put(entity.getTenantId(), entity);
                        yield 1;
                    }
                    case "updateById" -> 1;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static FeishuTenantTokenService tokenService() {
        return new FeishuTenantTokenService(new MockEnvironment()) {
            @Override
            public String token() {
                return "tenant-token";
            }
        };
    }

    @Test
    void createsTheTenantFolderOnFirstUseAndPersistsTheMapping() {
        Map<String, FeishuTenantLibraryEntity> rows = new HashMap<>();
        FakeDrive drive = new FakeDrive();
        FeishuTenantLibraryService service = new FeishuTenantLibraryService(
                inMemoryMapper(rows), drive, tokenService(), new MockEnvironment());

        FeishuTenantLibraryService.TenantLibrary library = service.ensureLibrary("school-a");

        assertThat(library.created()).isTrue();
        assertThat(library.folderToken()).isEqualTo("fld-1");
        assertThat(drive.created).containsExactly("mathagent-school-a");
        assertThat(rows).containsKey("school-a");
        assertThat(rows.get("school-a").getFolderName()).isEqualTo("mathagent-school-a");
    }

    @Test
    void reusedMappingDoesNotTouchTheDriveApiAtAll() {
        Map<String, FeishuTenantLibraryEntity> rows = new HashMap<>();
        FakeDrive first = new FakeDrive();
        FeishuTenantLibraryService service = new FeishuTenantLibraryService(
                inMemoryMapper(rows), first, tokenService(), new MockEnvironment());
        service.ensureLibrary("school-a");
        FakeDrive second = new FakeDrive();
        FeishuTenantLibraryService rerun = new FeishuTenantLibraryService(
                inMemoryMapper(rows), second, tokenService(), new MockEnvironment());

        FeishuTenantLibraryService.TenantLibrary library = rerun.ensureLibrary("school-a");

        assertThat(library.created()).isFalse();
        assertThat(second.created).isEmpty();
        assertThat(second.children).isEmpty();
    }

    @Test
    void adoptsPreexistingFolderWithTheSameNameWithoutCreatingADuplicate() {
        Map<String, FeishuTenantLibraryEntity> rows = new HashMap<>();
        FakeDrive drive = new FakeDrive();
        drive.children.put("mathagent-school-a", "fld-legacy");
        FeishuTenantLibraryService service = new FeishuTenantLibraryService(
                inMemoryMapper(rows), drive, tokenService(), new MockEnvironment());

        FeishuTenantLibraryService.TenantLibrary library = service.ensureLibrary("school-a");

        assertThat(library.folderToken()).isEqualTo("fld-legacy");
        assertThat(drive.created).isEmpty();
        assertThat(rows.get("school-a").getRootFolderToken()).isEqualTo("fld-legacy");
    }

    @Test
    void folderNameSanitizesControlAndPathCharacters() {
        assertThat(FeishuTenantLibraryService.folderName("school/a\\b:c", 1, "mathagent"))
                .isEqualTo("mathagent-school_a_b_c");
        assertThat(FeishuTenantLibraryService.folderName("school-a", 2, "lib"))
                .isEqualTo("lib-school-a-2");
        assertThatThrownBy(() -> new FeishuTenantLibraryService(
                inMemoryMapper(new HashMap<>()), new FakeDrive(), tokenService(), new MockEnvironment())
                .ensureLibrary(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
