package com.dunwugudao.replay.mapper.ck;

import com.dunwugudao.replay.vo.DragonTigerVO;
import com.dunwugudao.replay.vo.DtDetailVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 龙虎榜（S3）。dragon_tiger 个股榜 + dt_detail 席位明细。全部走 FINAL。
 */
@Mapper
public interface DragonTigerMapper {

    /** 某交易日龙虎榜个股（按净买额降序）。 */
    List<DragonTigerVO> selectDragonTiger(@Param("tradeDate") LocalDate tradeDate);

    /** 某交易日某股票龙虎榜席位明细（按 rank 升序）。 */
    List<DtDetailVO> selectDtDetail(@Param("tradeDate") LocalDate tradeDate,
                                    @Param("tsCode") String tsCode);

    /** 某交易日全部龙虎榜席位明细（按 ts_code, rank 升序），供 S3 聚合主力博弈与抱团席位。 */
    List<DtDetailVO> selectDtDetailByDate(@Param("tradeDate") LocalDate tradeDate);
}
