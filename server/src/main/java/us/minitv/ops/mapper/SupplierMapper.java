package us.minitv.ops.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import us.minitv.ops.row.SupplierRow;

@Mapper
public interface SupplierMapper {

    @Select("SELECT * FROM suppliers WHERE id = #{id}")
    SupplierRow findById(@Param("id") long id);
}
