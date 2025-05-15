package cm.bhz.app.dim;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * @Package com.lzr.domain.DimSkuInfoMsg
 * @Author lv.zirao
 * @Date 2025/5/15 15:45
 * @description:
 */

@AllArgsConstructor
@NoArgsConstructor
@Data
public class DimSkuInfoMsg implements Serializable {
    private String skuid;
    private String spuid;
    private String c3id;
    private String tname;
}