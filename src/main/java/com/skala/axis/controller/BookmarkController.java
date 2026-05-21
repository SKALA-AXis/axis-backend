package com.skala.axis.controller;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.config.AuthSecurity;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.BookmarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/bookmarks")
@RequiredArgsConstructor
public class BookmarkController {
    private final ApiContractFixtureService fixture;
    private final AuthProperties authProperties;
    private final BookmarkService bookmarkService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listBookmarks(
            @RequestParam Map<String, String> params,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(bookmarkService.list(AuthSecurity.requireUserId(authentication), params)));
        }
        return ResponseEntity.ok(ApiResponse.success(ApiContractFixtureService.mapOf(
                "items", fixture.todayCards(params).get("items")
        )));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> addBookmark(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(bookmarkService.add(AuthSecurity.requireUserId(authentication), request)));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(fixture.createdResult()));
    }

    @DeleteMapping("/{cardId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeBookmark(
            @PathVariable String cardId,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(bookmarkService.remove(AuthSecurity.requireUserId(authentication), cardId)));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.deletedResult("card_id", cardId)));
    }
}
