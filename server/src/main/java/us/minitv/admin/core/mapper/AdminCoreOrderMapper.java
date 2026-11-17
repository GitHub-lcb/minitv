package us.minitv.admin.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import us.minitv.billing.row.OrderRow;

import java.time.LocalDateTime;
import java.util.List;

/** orders statements used to compute monthly supplier settlements. */
@Mapper
public interface AdminCoreOrderMapper {

    /**
     * First-ever paid order per user (paid, not refunded), limited to orders paid
     * inside [start, end) whose source drama belongs to the supplier.
     */
    List<OrderRow> firstPaidOrdersForSupplier(@Param("supplierId") long supplierId,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);
}
