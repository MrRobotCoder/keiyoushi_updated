package eu.kanade.tachiyomi.extension.pt.randomscan.dto

import kotlinx.serialization.Serializable

@Serializable
class GeneroDto(
    val name: String
)

@Serializable
class CapituloDto(
    val id: Int,
    val num: Double,
    val data: String,
    val slug: String
)

@Serializable
class MangaDto(
    val id: Int,
    val capa: String,
    val titulo: String,
    val autor: String?,
    val artista: String?,
    val status: String,
    val sinopse: String,
    val tipo: String,
    val generos: List<GeneroDto>,
    val caps: List<CapituloDto>
)

@Serializable
class ObraDto(
    val id: Int
)

@Serializable
class CapituloPaginaDto(
    val id: Int,
    val obra: ObraDto,
    val files: Int
)

@Serializable
class MainPageMangaDto(
    val title: String,
    val capa: String,
    val slug: String
)

@Serializable
class MainPageDto(
    val lancamentos: List<MainPageMangaDto>,
    val top_10: List<MainPageMangaDto>
)

@Serializable
class SearchResponseMangaDto(
    val titulo: String,
    val capa: String,
    val slug: String
)

@Serializable
class SearchResponseDto(
    val obras: List<SearchResponseMangaDto>
)
