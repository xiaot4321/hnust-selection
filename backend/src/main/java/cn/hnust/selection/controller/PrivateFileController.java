package cn.hnust.selection.controller;

import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.PrivateFileAccessService;
import cn.hnust.selection.service.PrivateFileAccessService.AuthorizedFile;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/files")
@Validated
public class PrivateFileController {
    private final PrivateFileAccessService service;
    public PrivateFileController(PrivateFileAccessService service) { this.service = service; }
    @GetMapping("/{fileId}/content")
    public ResponseEntity<Resource> content(@PathVariable("fileId") @Positive Long fileId,
        @AuthenticationPrincipal AccountPrincipal actor) {
        AuthorizedFile file = service.open(actor, fileId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.getMediaType()))
            .contentLength(file.getSize()).cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.getFilename(), StandardCharsets.UTF_8).build().toString())
            .body(file.getResource());
    }
}
