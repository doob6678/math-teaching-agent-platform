package com.doob.mathagent.teacher;

import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherResourceStore;
import com.doob.mathagent.vector.service.TestVectorIndexService;

public final class TeacherResourceServiceFixture {

    private TeacherResourceServiceFixture() {
    }

    public static TeacherResourceService service(TeacherResourceStore resourceStore) {
        return service(resourceStore, new InMemoryTeacherDocumentBlockStore());
    }

    public static TeacherResourceService service(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore) {
        return new TeacherResourceService(
                resourceStore,
                TestVectorIndexService.successful(resourceStore, blockStore));
    }
}
