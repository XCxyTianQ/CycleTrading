package com.cycletrading.util;

/** 具备到期时间的领域对象（债券/期货交割/期货头寸/期权）。 */
public interface Matures {

    long matureAt();
}
