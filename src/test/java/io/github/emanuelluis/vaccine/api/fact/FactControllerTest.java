package io.github.emanuelluis.vaccine.api.fact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice only: no database, runs in `make test-fast`. */
@WebMvcTest(FactController.class)
class FactControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FactService service;

    @Test
    void missingDatesAreForwardedAsNullAndPagingIsApplied() throws Exception {
        when(service.getFactAplicacaoVacina(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        mvc.perform(get("/api/fact").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(service).getFactAplicacaoVacina(isNull(), isNull(), eq(PageRequest.of(2, 10)));
    }
}
