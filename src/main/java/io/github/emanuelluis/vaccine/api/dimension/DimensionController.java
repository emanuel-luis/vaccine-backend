package io.github.emanuelluis.vaccine.api.dimension;

import java.util.List;
import org.springframework.beans.factory.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@CrossOrigin(origins = "http://localhost:8080")
@RestController
@RequestMapping("/api")
public class DimensionController {
    @Autowired
    private DimensionService service;

    @RequestMapping(value = "/dimension", method = RequestMethod.GET)
    public List<IDimensionFilial> getDimension(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "500") int size) {
        Pageable pageRequest = PageRequest.of(page, size);

        return service.getDimensionFilial(pageRequest).toList();
    }

    @RequestMapping(value = "/dimension/render", method = RequestMethod.GET)
    public ModelAndView renderDimension(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String cidade,
            ModelMap model) {
        Pageable pageRequest = PageRequest.of(page, 22);

        boolean cidadeIsEmpty = cidade == null || cidade.isEmpty();
        boolean estadoIsEmpty = estado == null || estado.isEmpty();

        if (cidadeIsEmpty && estadoIsEmpty) {
            model.put("filialList", service.listAll(pageRequest));
        } else if (!estadoIsEmpty && cidadeIsEmpty) {
            model.put("filialList", service.listByEstado(estado, pageRequest));
        } else if (!cidadeIsEmpty && estadoIsEmpty) {
            model.put("filialList", service.listByMunicipio(cidade, pageRequest));
        } else {
            model.put("filialList", service.listByMunicipioAndEstado(cidade, estado, pageRequest));
        }
        return new ModelAndView("filial", model);
    }
}
