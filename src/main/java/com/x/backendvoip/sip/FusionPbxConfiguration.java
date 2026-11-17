package com.x.backendvoip.sip;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FusionPbxProperties.class)
public class FusionPbxConfiguration {}
