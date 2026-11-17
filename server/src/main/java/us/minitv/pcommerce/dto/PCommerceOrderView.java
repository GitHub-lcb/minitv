package us.minitv.pcommerce.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** One row of the order history join (orders + plan_versions + plans). */
@Data
public class PCommerceOrderView {
    private String orderNo;
    private String planCode;
    private String planNameEn;
    private String planNameZh;
    private Integer amountCents;
    private String currency;
    private String billingMode;
    private String status;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
