package cm.bhz.app.dim;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Package com.lzr.domain.DimCategoryCompare
 * @Author lv.zirao
 * @Date 2025/5/14 14:11
 * @description:
 */

@AllArgsConstructor
@NoArgsConstructor
@Data
public class DimCategoryCompare {
    private Integer id;
    private String categoryName;
    private String searchCategory;
}
