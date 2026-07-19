package com.doob.mathagent.teacher.vo;

/**
 * Permission-checked asset metadata returned by backend services.
 *
 * @param assetId public opaque asset id
 * @param tenantId tenant that owns the asset
 * @param ownerSubjectId teacher/admin subject that owns private copies
 * @param documentId source document id
 * @param blockId optional parsed block id
 * @param permissionScope visibility scope inherited from the source document
 * @param sourcePath stable source path inside the uploaded package or Feishu staging folder
 * @param pageNo PDF page number when available
 * @param providerAssetId provider-local asset id, never exposed as an auth token
 * @param checksum content checksum used for idempotent updates
 * @param mimeType asset media type
 * @param width image width when known
 * @param height image height when known
 * @param storageKey backend relative storage key, never returned directly by controllers
 * @param status lifecycle status
 */
public record TeacherResourceAssetResponse(
        String assetId,
        String tenantId,
        String ownerSubjectId,
        String documentId,
        String blockId,
        String permissionScope,
        String sourcePath,
        Integer pageNo,
        String providerAssetId,
        String checksum,
        String mimeType,
        Integer width,
        Integer height,
        String storageKey,
        String status) {
}
