package com.licitaia.feature.tender.di

import com.licitaia.domain.repository.ProposalPdfGenerator
import com.licitaia.feature.tender.pdf.AndroidProposalPdfGenerator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TenderModule {
    @Binds
    @Singleton
    abstract fun bindProposalPdfGenerator(impl: AndroidProposalPdfGenerator): ProposalPdfGenerator
}
