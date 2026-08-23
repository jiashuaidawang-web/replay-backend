package com.dunwugudao.replay.trader;

/**
 * 资金层角色：资金流信号在战法中的定位（正交信号，可开关）。
 * <ul>
 *   <li>IGNORE：资金层不参与该战法判定（NONE）；</li>
 *   <li>FILTER：资金层只做否决——大单净流出(CONTRA)时拦截，否则放行(FILTER_PASS)；</li>
 *   <li>CONFIRM：资金层做强确认——必须大单净主动买入达标(CONFIRM)才允许触发。</li>
 * </ul>
 */
public enum CapitalRole {
    IGNORE, FILTER, CONFIRM
}
