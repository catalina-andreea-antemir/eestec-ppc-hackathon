package ro.filegrid.download;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configurația {@code filegrid.download} (C8).
 *
 * @param linkSecret       cheia HMAC a link-urilor semnate, în base64 (variabila {@code DOWNLOAD_LINK_SECRET}, generată
 *                         de {@code scripts/start.*}); cel puțin 32 de octeți după decodare
 * @param linkTtl          valabilitatea unui link (15 minute)
 * @param preVerifyMaxSize fișierele de până la această mărime se verifică (citire completă pentru hash) ÎNAINTE de
 *                         trimitere; cele mai mari se verifică în timpul trimiterii (16 MB implicit)
 */
@ConfigurationProperties("filegrid.download")
public record DownloadProperties(String linkSecret,
                                 @DefaultValue("15m") Duration linkTtl,
                                 @DefaultValue("16MB") DataSize preVerifyMaxSize) {
}
