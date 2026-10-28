package org.mobyle.data.repository

import org.mobyle.data.local.maratonas.MaratonaDataSource
import org.mobyle.domain.repository.MaratonaRepository

class MaratonaRepositoryImpl(maratonaDataSource: MaratonaDataSource) : MaratonaRepository by maratonaDataSource
