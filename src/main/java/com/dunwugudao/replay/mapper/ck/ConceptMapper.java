package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.entity.Concept;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 题材静态属性（ClickHouse）—— 读 board_basic 派生、写 concept 表（均为 CK）。
 *
 * <p>由 CkMybatisConfig 扫描本包，绑定 ck 数据源。
 * concept 为 ReplacingMergeTree，重算时用 deleteAll 清全表后整批重写（量级小，按全量而非按日）。
 */
@Mapper
public interface ConceptMapper {

    int deleteAll();

    int count();

    int insertBatch(@Param("list") List<Concept> list);

    List<Concept> selectAll();

    /** 仅取真题材，供主线识别 / 炒作因子过滤伪概念。 */
    List<Concept> selectRealThemes();
}
