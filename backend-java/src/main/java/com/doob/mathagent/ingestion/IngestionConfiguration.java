package com.doob.mathagent.ingestion;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the explicit ingestion-path configuration without deriving paths from the process environment. */
@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfiguration { }
