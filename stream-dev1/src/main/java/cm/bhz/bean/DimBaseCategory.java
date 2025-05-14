package cm.bhz.bean;
/**
 * @Package cm.bhz.bean.DimBaseCategory
 * @Author huizhong.bai
 * @Date 2025/5/14 19:28
 * @description:
 */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * @Package cm.bhz.bean.DimBaseCategory
 * @Author huizhong.bai
 * @Date 2025/5/14 19:28
 * @description:
 */

@AllArgsConstructor
@NoArgsConstructor
@Data
public class DimBaseCategory implements Serializable {
    private String id;
    private String b3name;
    private String b2name;
    private String b1name;
}
