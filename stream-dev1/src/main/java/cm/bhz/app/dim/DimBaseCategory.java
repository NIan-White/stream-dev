package cm.bhz.app.dim;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * @Package com.lzr.domain.DimBaseCategory
 * @Author lv.zirao
 * @Date 2025/5/14 14:10
 * @description: base category all data
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class DimBaseCategory implements Serializable {

    private String id;
    private String name3;
    private String name2;
    private String name1;


}
