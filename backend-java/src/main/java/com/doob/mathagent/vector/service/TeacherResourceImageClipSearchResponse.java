package com.doob.mathagent.vector.service;

import java.util.List;

/** Search response identifying the private CLIP collection used for teacher PDF page assets. */
public record TeacherResourceImageClipSearchResponse(
        String query,
        String collectionName,
        int hitCount,
        List<TeacherResourceImageClipHit> hits) {
}
