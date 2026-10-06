package com.licitaia.core.data.di

import javax.inject.Qualifier

/** Escopo de aplicação para trabalhos em segundo plano da camada de dados (ex.: análise de IA). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DataScope
