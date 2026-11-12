package com.creastrix.platform.localcatalog;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local-catalog")
class LocalCatalogController {
    private final LocalCatalogReader reader;

    LocalCatalogController(LocalCatalogReader reader) {
        this.reader = reader;
    }

    @GetMapping(LocalCatalogConfiguration.PRODUCTS_PATH)
    ResponseEntity<LocalCatalogReader.Catalog> products() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reader.read());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        // An unavailable source must not be reported as a successful empty catalog.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "catalog_unavailable"));
    }
}
