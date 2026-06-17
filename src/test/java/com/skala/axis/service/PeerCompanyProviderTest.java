package com.skala.axis.service;

import com.skala.axis.domain.PeerCompany;
import com.skala.axis.repository.PeerCompanyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeerCompanyProviderTest {

    @Mock
    private PeerCompanyRepository peerCompanyRepository;

    @InjectMocks
    private PeerCompanyProvider provider;

    private static PeerCompany peer(String id, String name) {
        PeerCompany p = mock(PeerCompany.class);
        when(p.getId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        return p;
    }

    @Test
    void displayNameUsesDbValueWhenPresent() {
        PeerCompany samsung = peer("samsung_sds", "삼성SDS(DB)");
        PeerCompany lgCns = peer("lg_cns", "LG CNS(DB)");
        when(peerCompanyRepository.findAll()).thenReturn(List.of(samsung, lgCns));

        assertThat(provider.displayName("samsung_sds")).isEqualTo("삼성SDS(DB)");
        assertThat(provider.displayName("lg_cns")).isEqualTo("LG CNS(DB)");
    }

    @Test
    void displayNameCachesAfterFirstLoad() {
        PeerCompany samsung = peer("samsung_sds", "삼성SDS(DB)");
        when(peerCompanyRepository.findAll()).thenReturn(List.of(samsung));

        provider.displayName("samsung_sds");
        provider.displayName("samsung_sds");
        provider.displayName("samsung_sds");

        verify(peerCompanyRepository, times(1)).findAll();
    }

    @Test
    void displayNameReturnsPeerIdWhenUnknown() {
        PeerCompany samsung = peer("samsung_sds", "삼성SDS(DB)");
        when(peerCompanyRepository.findAll()).thenReturn(List.of(samsung));

        assertThat(provider.displayName("unknown_peer")).isEqualTo("unknown_peer");
    }

    @Test
    void displayNameReturnsPlaceholderForNullOrBlank() {
        assertThat(provider.displayName(null)).isEqualTo("Peer사");
        assertThat(provider.displayName("")).isEqualTo("Peer사");
        assertThat(provider.displayName("   ")).isEqualTo("Peer사");
        // null/blank 은 DB 조회 없이 즉시 반환
        verify(peerCompanyRepository, never()).findAll();
    }

    @Test
    void fallsBackToStaticNamesWhenDbEmpty() {
        when(peerCompanyRepository.findAll()).thenReturn(List.of());

        assertThat(provider.displayName("hyundai_autoever")).isEqualTo("현대오토에버");
        assertThat(provider.displayName("posco_dx")).isEqualTo("포스코DX");
    }

    @Test
    void fallsBackToStaticNamesWhenDbThrows() {
        when(peerCompanyRepository.findAll()).thenThrow(new RuntimeException("db down"));

        assertThat(provider.displayName("samsung_sds")).isEqualTo("삼성SDS");
        assertThat(provider.displayName("lg_cns")).isEqualTo("LG CNS");
    }
}
