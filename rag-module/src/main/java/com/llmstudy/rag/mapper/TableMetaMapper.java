package com.llmstudy.rag.mapper;

import com.llmstudy.rag.entity.TableMeta;
import com.llmstudy.rag.enums.TableMetaStatus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TableMetaMapper {

    @Select("SELECT * FROM table_meta WHERE doc_id = #{docId} ORDER BY sheet_index")
    List<TableMeta> findByDocId(@Param("docId") String docId);

    @Insert("""
            INSERT INTO table_meta
            (doc_id, sheet_index, sheet_name, table_name, column_mapping, row_count, status)
            VALUES
            (#{docId}, #{sheetIndex}, #{sheetName}, #{tableName},
             #{columnMapping}, #{rowCount}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TableMeta meta);

    @Update("""
            UPDATE table_meta
            SET row_count = #{rowCount}, status = #{status}
            WHERE doc_id = #{docId} AND sheet_index = #{sheetIndex}
            """)
    int markImportedValue(@Param("docId") String docId,
                          @Param("sheetIndex") int sheetIndex,
                          @Param("rowCount") long rowCount,
                          @Param("status") String status);

    default int markImported(String docId, int sheetIndex, long rowCount) {
        return markImportedValue(
                docId, sheetIndex, rowCount, TableMetaStatus.IMPORTED.value());
    }

    @Delete("DELETE FROM table_meta WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") String docId);
}
