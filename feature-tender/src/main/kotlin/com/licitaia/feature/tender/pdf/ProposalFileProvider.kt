package com.licitaia.feature.tender.pdf

import androidx.core.content.FileProvider

/** Subclasse própria para evitar conflito de manifest com outros FileProviders do app. */
class ProposalFileProvider : FileProvider()
