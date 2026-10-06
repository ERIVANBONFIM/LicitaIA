package com.licitaia.core.data.repository

import com.licitaia.core.data.session.SessionHolder
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import javax.inject.Inject
import javax.inject.Singleton

/** Local authorization boundary: always rechecked when reading or changing tenant data. */
@Singleton
class RepositoryAccess @Inject constructor(private val holder: SessionHolder) {
    fun owns(companyId: Long): Boolean = holder.current?.let {
        !it.user.demo && companyId > 0 && it.activeCompany.id == companyId && companyId in it.user.companyIds
    } == true
    fun requireCompany(companyId: Long, permission: Permission? = null) {
        check(owns(companyId)) { "Acesso negado à empresa. Selecione sua empresa e entre novamente." }
        if (permission != null) check(holder.current?.user?.role?.let { Rbac.can(it, permission) } == true) {
            "Seu perfil não tem permissão para esta operação."
        }
    }
}
