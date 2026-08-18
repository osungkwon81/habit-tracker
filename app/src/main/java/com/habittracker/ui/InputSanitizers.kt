package com.habittracker.ui

/**
 * 숫자 입력 필드에서 공통으로 사용하는 문자열 확장 함수다.
 *
 * 확장 함수는 String 클래스를 상속하거나 수정하지 않고도 `value.digitsOnly()`처럼
 * 도메인에 맞는 읽기 쉬운 API를 추가하는 Kotlin 기능이다.
 */
fun String.digitsOnly(): String = filter(Char::isDigit)
