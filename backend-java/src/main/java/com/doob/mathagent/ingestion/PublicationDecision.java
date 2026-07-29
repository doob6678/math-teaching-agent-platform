package com.doob.mathagent.ingestion;

/** Explicit gate result retained in audit evidence rather than relying on an implicit boolean. */
public record PublicationDecision(boolean publishable, String reason) { }
