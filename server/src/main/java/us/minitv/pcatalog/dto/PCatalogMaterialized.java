package us.minitv.pcatalog.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Materialised page: client items plus how many plan entries were examined. */
@Data
public class PCatalogMaterialized {
    private List<Map<String, Object>> items = new ArrayList<>();
    private int consumed;
}
