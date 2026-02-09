package ru.kazantsev.nsd.modules.dictionaryViewAppController

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.hibernate.ScrollMode
import org.hibernate.ScrollableResults
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.query.Query
import ru.kazantsev.nsd.modules.web_api_components.Preferences
import ru.kazantsev.nsd.modules.web_api_components.RequestProcessor
import ru.kazantsev.nsd.modules.web_api_components.WebApiException
import ru.kazantsev.nsd.modules.web_api_components.WebApiUtilities
import ru.naumen.core.server.SpringContext
import ru.naumen.core.server.script.api.injection.InjectApi
import ru.naumen.core.shared.dto.ISDtObject
import ru.naumen.metainfo.server.MetainfoService
import ru.naumen.metainfo.shared.elements.Catalog

import static ru.kazantsev.nsd.sdk.global_variables.ApiPlaceholder.*

abstract class Order {
    enum By {
        TITLE("title", "d.title", "d.rootTitle"),
        CODE("code", "d.code", "d.rootCode"),
        PARENT_TITLE("parentTitle", "p.title", null),
        PARENT_CODE("parentCode", "p.code", null)

        String code
        String dbCodeForFlat
        String dbCodeForHierarchy

        By(String code, String dbCodeForFlat, String dbCodeForHierarchy) {
            this.dbCodeForFlat = dbCodeForFlat
            this.dbCodeForHierarchy = dbCodeForHierarchy
            this.code = code
        }

        @JsonCreator
        static By getByCode(String code) {
            By value = values().find { it.code == code }
            if (!value) throw new IllegalArgumentException("Не удалось получить значение enum по коду " + code)
            return value
        }

        @JsonValue
        String getCode() {
            return this.code
        }

    }

    enum Direction {
        ASC("ascend", "ASC"),
        DESC("descend", "DESC")

        String code
        String dbCode

        Direction(String code, String dbCode) {
            this.code = code
            this.dbCode = dbCode
        }

        @JsonCreator
        static Direction getByCode(String code) {
            Direction value = values().find { it.code == code }
            if (!value) throw new IllegalArgumentException("Не удалось получить значение enum по коду " + code)
            return value
        }

        @JsonValue
        String getCode() {
            return this.code
        }
    }

}

abstract class Constants {
    static final List<String> LICENSES = ['concurrent', 'named']
    static final List<Integer> PAGE_SIZES = [5, 20, 50, 100]
    static final EXPORT_FILE_LIMIT = 10000
    static final Boolean ENABLE_LOGGING = false
    static final String LOG_PREFIX = 'dictionaryViewAppController'

}

enum TableMode {
    FLAT("flat"),
    HIERARCHY("hierarchy")

    String code

    TableMode(String code) {
        this.code = code
    }

    @JsonCreator
    static TableMode getByCode(String code) {
        TableMode value = values().find { it.code == code }
        if (!value) throw new IllegalArgumentException("Не удалось получить значение enum по коду " + code)
        return value
    }

    @JsonValue
    String getCode() {
        return this.code
    }
}

abstract class Dto {

    static class FileInfo {
        Long fileId
        String elementUuid
    }

    /** Опция фильтрации для фронта */
    static class SelectOption {
        String label
        String value
    }

    static class CatalogType {
        String label
        String value
        Boolean isWithFolders
        Boolean isFlat
    }

    static class Page {
        List<Element> elements
        Preferences preferences
        Filter filter
        Sorter sorter
        Pager pager
    }

    static class SelectOptionsPage {
        List<SelectOption> options
        Pager pager
    }

    static class PageParams {
        Preferences preferences
        Filter filter
        Sorter sorter
        Pager pager
    }

    static class ExportParams {
        Filter filter
        Sorter sorter
        Integer limit
        Integer offset
    }


    static class Sorter {
        Order.By columnKey
        Order.Direction order
    }

    static class Pager {
        Integer current
        Integer pageSize
        Integer total
    }

    static class BaseObject {
        String uuid
        String title
    }

    static class InitialData {
        Boolean licenced
        Boolean isAdmin
        Integer exportFileLimit
        List<CatalogType> types = []
    }

    static class Element extends BaseObject {
        Long key
        String color
        Boolean removed
        String code
        String fileUuid
        Element parent
        Boolean folder
        List<Element> children
        Boolean isSearched = false
    }

    static class Filter {
        String title
        String code
        Boolean folder
        Boolean removed
        String parentTitle
        String parentObject
        String parentCode
        Boolean parentFolder
        Boolean parentRemoved
    }

    static class Preferences {
        TableMode tableMode
    }

    static class ConditionsAndParamsPair {
        List<String> conditions
        Map<String, Object> params
    }

    static class QueryAndParamsPair {
        String query
        Map<String, Object> params
    }
}

abstract class Utilities {

    @InjectApi
    private static class ApiHolder {}

    private static ApiHolder apiHolder = new ApiHolder()

    static logInfo(String message) {
        if (Constants.ENABLE_LOGGING) apiHolder.logger.info("[${Constants.LOG_PREFIX}] " + message)
    }

    static String getUuid(String metaCode, Long id) {
        if (!metaCode || !id) return null
        return metaCode + '$' + id.toString()
    }

    static Boolean isUserLicensed(ISDtObject user) {
        if (user == null) return true
        else return Constants.LICENSES.any { ((String) user.license).contains(it) }
    }

    static Preferences prefs = new Preferences().assertUserIsLicensed()
}

abstract class QueryBuilderService {

    abstract class FilterOptions {

        static Dto.ConditionsAndParamsPair getConditionsAndParamsPair(Boolean withFolders, Boolean flat, Boolean folder, String search) {
            Map<String, Object> params = [:]
            List<String> conditions = []
            Utilities.logInfo("folder " + folder.toString())
            if (folder) {
                params.put("folder", true)
                conditions.add("d.folder = :folder")
                if (search != null && !search.isEmpty()) {
                    params.put("titleFilter", "%" + search.trim() + "%")
                    conditions.add("d.title.ru LIKE :titleFilter")
                }
            } else {
                if (search != null && !search.isEmpty()) {
                    params.put("titleFilter", "%" + search.trim() + "%")
                    conditions.add("d.title.ru LIKE :titleFilter")
                }
                if (withFolders && flat) {
                    params.put("folder", true)
                    conditions.add("d.folder = :folder")
                }
                if (!withFolders && !flat) {
                    params.put("folder", false)
                    conditions.add("d.folder = :folder")
                }
                if (!withFolders && flat) {
                    conditions.add("1 = 2")
                }
                if (withFolders && !flat) {
                    conditions.add("1 = 1")
                }
            }
            return new Dto.ConditionsAndParamsPair(params: params, conditions: conditions)
        }

        static Dto.QueryAndParamsPair getSelectQueryAndParams(String metaCode, Boolean withFolders, Boolean flat, Boolean folder, String search) {
            List<String> basic = [
                    "SELECT",
                    "d.id,", //0
                    'd.title,', //1
                    'd.folder', //2
                    "FROM ${metaCode} d"
            ]

            Dto.ConditionsAndParamsPair cpPair = getConditionsAndParamsPair(withFolders, flat, folder, search)

            String sort = "ORDER BY ${Order.By.TITLE.dbCodeForFlat} ${Order.Direction.ASC.dbCode}"

            List<String> arr = [basic.join("\n")]
            if (cpPair.conditions.size() > 0) {
                arr.add("WHERE")
                arr.add(cpPair.conditions.join("\nAND "))
            }
            arr.add(sort)
            String query = arr.join('\n')

            return new Dto.QueryAndParamsPair(params: cpPair.params, query: query)
        }

        static Dto.QueryAndParamsPair getCountQueryAndParams(String metaCode, Boolean withFolders, Boolean flat, Boolean folder, String search) {
            List<String> basic = [
                    "SELECT",
                    "COUNT(*)",
                    "FROM ${metaCode} d"
            ]

            Dto.ConditionsAndParamsPair cpPair = getConditionsAndParamsPair(withFolders, flat, folder, search)

            List<String> arr = [basic.join("\n")]
            if (cpPair.conditions.size() > 0) {
                arr.add("WHERE")
                arr.add(cpPair.conditions.join("\nAND "))
            }
            String query = arr.join('\n')

            return new Dto.QueryAndParamsPair(params: cpPair.params, query: query)
        }

    }

    abstract class FlatList {
        static Dto.QueryAndParamsPair getSelectQueryAndParams(String metaCode, Dto.Filter filter, Order.By orderBy, Order.Direction orderDirection) {
            List<String> basic = [
                    "SELECT",
                    "d.id,", //0
                    'd.title,', //1
                    'd.code,', //2
                    "REPLACE(d.color, 'color: ', ''),", //3
                    'd.removed,', //4
                    'd.folder,', //5
                    'p.id,', //6
                    'p.title,', //7
                    'p.code,', //8
                    'p.folder,', //9
                    "REPLACE(p.color, 'color: ', ''),", //10
                    "p.removed", //11
                    "FROM ${metaCode} d",
                    "LEFT JOIN d.parent p",
            ]

            Dto.ConditionsAndParamsPair cpPair = getConditionsAndParamsPair(filter)

            String sort = "ORDER BY ${orderBy.dbCodeForFlat} ${orderDirection.dbCode}"

            List<String> arr = [basic.join("\n")]
            if (cpPair.conditions.size() > 0) {
                arr.add("WHERE")
                arr.add(cpPair.conditions.join("\nAND "))
            }
            arr.add(sort)
            String query = arr.join('\n')

            return new Dto.QueryAndParamsPair(params: cpPair.params, query: query)
        }

        static Dto.QueryAndParamsPair getCountQueryAndParams(String metaCode, Dto.Filter filter) {
            List<String> basic = [
                    "SELECT",
                    "COUNT(*)", //0
                    "FROM ${metaCode} d",
                    "LEFT JOIN d.parent p"
            ]

            Dto.ConditionsAndParamsPair cpPair = getConditionsAndParamsPair(filter)

            List<String> arr = [basic.join("\n")]
            if (cpPair.conditions.size() > 0) {
                arr.add("WHERE")
                arr.add(cpPair.conditions.join("\nAND "))
            }
            String query = arr.join('\n')

            return new Dto.QueryAndParamsPair(params: cpPair.params, query: query)
        }

        static Dto.ConditionsAndParamsPair getConditionsAndParamsPair(Dto.Filter filter, List<Long> parentIds = null) {
            Map<String, Object> params = [:]
            List<String> conditions = []
            //params.put('metaCode', metaCode)
            if (filter?.removed != null) {
                params.put("removedFilter", filter.removed)
                conditions.add("d.removed = :removedFilter")
            }
            if (filter?.folder != null) {
                params.put("folderFilter", filter.folder)
                conditions.add("d.folder = :folderFilter")
            }
            if (filter?.title) {
                params.put("titleFilter", "%" + filter.title.trim() + "%")
                conditions.add("d.title.ru LIKE :titleFilter")
            }
            if (filter?.code) {
                params.put("codeFilter", "%" + filter.code.trim() + "%")
                conditions.add("d.code LIKE :codeFilter")
            }
            if (filter?.parentTitle) {
                params.put("parentTitleFilter", "%" + filter.parentTitle.trim() + "%")
                conditions.add("p.title.ru LIKE :parentTitleFilter")
            }
            if (filter?.parentCode) {
                params.put("parentCodeFilter", "%" + filter.parentCode.trim() + "%")
                conditions.add("p.code LIKE :parentCodeFilter")
            }
            if (filter?.parentFolder != null) {
                params.put("parentFolderFilter", filter.parentFolder)
                conditions.add("p.folder = :parentFolderFilter")
            }
            if (filter?.parentRemoved != null) {
                params.put("parentRemovedFilter", filter.parentRemoved)
                conditions.add("p.removed = :parentRemovedFilter")
            }
            if (parentIds) {
                params.put("parentIdsFilter", parentIds)
                conditions.add("p.id IN :parentIdsFilter")
            }
            return new Dto.ConditionsAndParamsPair(params: params, conditions: conditions)
        }
    }

    abstract class HierarchyList {
        static Dto.QueryAndParamsPair getSelectQueryAndParamsForRoot(String metaCode, Dto.Filter filter, Order.By orderBy, Order.Direction orderDirection) {
            List<String> basic = [
                    "WITH Tree as (",
                    "   SELECT ",
                    "       root.id as id, ",
                    "       root.removed as removed, ",
                    "       root.code as code,",
                    "       root.title.ru as title,",
                    "       REPLACE(root.color, 'color: ', '') as color,",
                    "       root.folder as folder,",
                    "       '' || root.id as rootId,",
                    "       '' || root.removed as rootRemoved,",
                    "       '' || root.code as rootCode,",
                    "       '' || root.title.ru as rootTitle,",
                    "       '' || REPLACE(root.color, 'color: ', '') as rootColor,",
                    "       '' || root.folder as rootFolder,",
                    "       0 as level",
                    "   FROM ${metaCode} root",
                    "   WHERE root.parent is null",
                    "   UNION ALL",
                    "   SELECT",
                    "       child.id as id, ",
                    "       child.removed as removed,",
                    "       child.code as code,",
                    "       child.title.ru as title,",
                    "       REPLACE(child.color, 'color: ', '') as color,",
                    "       child.folder as folder,",
                    "       parent.rootId as rootId, ",
                    "       parent.rootRemoved as rootRemoved, ",
                    "       parent.rootCode as rootCode, ",
                    "       parent.rootTitle as rootTitle, ",
                    "       parent.rootColor as rootColor, ",
                    "       parent.rootFolder as rootFolder, ",
                    "       level + 1 as level",
                    "   FROM Tree parent",
                    "   JOIN ${metaCode} child ON child.parent.id = parent.id",
                    ")",
                    "SELECT DISTINCT",
                    "   d.rootId,",
                    "   d.rootRemoved,",
                    "   d.rootCode,",
                    "   d.rootTitle,",
                    "   d.rootColor,",
                    "   d.rootFolder",
                    "FROM Tree d"
            ]

            Dto.ConditionsAndParamsPair cpPair = getConditionsAndParamsPair(filter)

            String sort = "ORDER BY ${orderBy.dbCodeForHierarchy} ${orderDirection.dbCode}"

            List<String> arr = [basic.join("\n")]
            if (cpPair.conditions.size() > 0) {
                arr.add("WHERE")
                arr.add(cpPair.conditions.join("\nAND "))
            }
            arr.add(sort)
            String query = arr.join('\n')

            return new Dto.QueryAndParamsPair(params: cpPair.params, query: query)
        }

        static Dto.QueryAndParamsPair getSelectQueryAndParamsForChildren(String metaCode, List<Long> ids) {
            List<String> basic = [
                    "WITH Tree as (",
                    "   SELECT ",
                    "       child.id as id, ",
                    "       child.removed as removed, ",
                    "       child.code as code,",
                    "       child.title.ru as title,",
                    "       REPLACE(child.color, 'color: ', '') as color,",
                    "       child.folder as folder,",
                    "       child.parent.id as parentId,",
                    "       1 as level",
                    "   FROM ${metaCode} child",
                    "   WHERE child.parent.id IN :ids",
                    "   UNION ALL",
                    "   SELECT",
                    "       grandchild.id as id,",
                    "       grandchild.removed as removed,",
                    "       grandchild.code as code,",
                    "       grandchild.title.ru as title,",
                    "       REPLACE(grandchild.color, 'color: ', '') as color,",
                    "       grandchild.folder as folder,",
                    "       grandchild.parent.id as parentId,",
                    "       level + 1 as level",
                    "   FROM Tree parent",
                    "   JOIN ${metaCode} grandchild ON grandchild.parent.id = parent.id",
                    ")",
                    "SELECT DISTINCT",
                    "   d.id,", ///0
                    "   d.removed,", //1
                    "   d.code,", //2
                    "   d.title,", //3
                    "   d.color,", //4
                    "   d.folder,", //5
                    "   d.parentId,", //6
                    "   d.level", //7
                    "FROM Tree d"
            ]

            return new Dto.QueryAndParamsPair(params: ['ids': ids], query: basic.join('\n'))
        }

        static Dto.QueryAndParamsPair getCountQueryAndParams(String metaCode, Dto.Filter filter) {
            List<String> basic = [
                    "WITH Tree as (",
                    "   SELECT ",
                    "       root.id as id, ",
                    "       root.removed as removed, ",
                    "       root.code as code,",
                    "       root.title.ru as title,",
                    "       REPLACE(root.color, 'color: ', '') as color,",
                    "       root.folder as folder,",
                    "       '' || root.id as rootId",
                    "   FROM ${metaCode} root",
                    "   WHERE root.parent is null",
                    "   UNION ALL",
                    "   SELECT",
                    "       child.id as id, ",
                    "       child.removed as removed,",
                    "       child.code as code,",
                    "       child.title.ru as title,",
                    "       REPLACE(child.color, 'color: ', '') as color,",
                    "       child.folder as folder,",
                    "       parent.rootId as rootId ",
                    "   FROM Tree parent",
                    "   JOIN ${metaCode} child ON child.parent.id = parent.id",
                    ")",
                    "SELECT COUNT(DISTINCT d.rootId)",
                    "FROM Tree d"
            ]

            Dto.ConditionsAndParamsPair cpPair = getConditionsAndParamsPair(filter)

            List<String> arr = [basic.join("\n")]
            if (cpPair.conditions.size() > 0) {
                arr.add("WHERE")
                arr.add(cpPair.conditions.join("\nAND "))
            }
            String query = arr.join('\n')

            return new Dto.QueryAndParamsPair(params: cpPair.params, query: query)
        }

        static Dto.ConditionsAndParamsPair getConditionsAndParamsPair(Dto.Filter filter) {
            Map<String, Object> params = [:]
            List<String> conditions = []
            //params.put('metaCode', metaCode)
            if (filter?.removed != null) {
                params.put("removedFilter", filter.removed)
                conditions.add("d.removed = :removedFilter")
            }
            if (filter?.folder != null) {
                params.put("folderFilter", filter.folder)
                conditions.add("d.folder = :folderFilter")
            }
            if (filter?.title) {
                params.put("titleFilter", "%" + filter.title.trim() + "%")
                conditions.add("d.title LIKE :titleFilter")
            }
            if (filter?.code) {
                params.put("codeFilter", "%" + filter.code.trim() + "%")
                conditions.add("d.code LIKE :codeFilter")
            }
            return new Dto.ConditionsAndParamsPair(params: params, conditions: conditions)
        }
    }
}

@InjectApi
class DataService {

    static Dto.Element createDtoFromISDtObject(ISDtObject object) {
        ISDtObject parent = object.parent as ISDtObject
        return new Dto.Element(
                title: object.getTitle(),
                uuid: object.getUUID(),
                code: object.code,
                folder: object.folder,
                parent: parent ? new Dto.Element(uuid: parent.getUUID(), title: parent.getTitle()) : null,
                removed: object.removed,
                color: (object.color as String)?.replace("color: ", '')
        )
    }

    private static Dto.Element createDtoForHierarchyList(Object[] dbResult, String metaCode) {
        return new Dto.Element(
                key: dbResult[0] as Long,
                uuid: Utilities.getUuid(metaCode, dbResult[0] as Long),
                removed: dbResult[1],
                code: dbResult[2],
                title: dbResult[3],
                color: dbResult[4],
                folder: dbResult[5]
        )
    }

    private static Dto.Element createDtoForFlatList(Object[] dbResult, String metaCode) {
        return new Dto.Element(
                key: dbResult[0] as Long,
                uuid: Utilities.getUuid(metaCode, dbResult[0] as Long),
                title: dbResult[1],
                code: dbResult[2],
                color: dbResult[3],
                removed: dbResult[4],
                folder: dbResult[5],
                parent: dbResult[6] ? new Dto.Element(
                        uuid: Utilities.getUuid(metaCode, dbResult[6] as Long),
                        title: dbResult[7],
                        code: dbResult[8],
                        folder: dbResult[9],
                        color: dbResult[10],
                        removed: dbResult[11]
                ) : null
        )
    }

    @SuppressWarnings('GrMethodMayBeStatic')
    List<Dto.FileInfo> getFileInfos(List<String> uuids) {
        String qString = [
                "SELECT",
                "f.id,", //0
                'f.source', //1
                "FROM file f",
                "WHERE f.source IN :uuids"
        ].join('\n')
        def query = api.db.query(qString).set(['uuids': uuids])
        def result = query.list() as List<Object[]>
        return result.collect { new Dto.FileInfo(fileId: it[0] as Long, elementUuid: it[1] as String) }
    }

    @SuppressWarnings('GrMethodMayBeStatic')
    List<Dto.SelectOption> getFilterOptions(
            String metaCode,
            Boolean withFolders,
            Boolean isFlat,
            Boolean folder,
            Integer offset,
            Integer limit,
            String search
    ) {
        def qpPair = QueryBuilderService.FilterOptions.getSelectQueryAndParams(metaCode, withFolders, isFlat, folder, search)
        def query = api.db.query(qpPair.query).set(qpPair.params)
        Utilities.logInfo(qpPair.query)
        Utilities.logInfo("getFilterOptions")
        query.setFirstResult(offset)
        query.setMaxResults(limit)
        List<Object[]> dbResults = query.list() as List<Object[]>
        return dbResults.collect {
            new Dto.SelectOption(
                    label: it[1] as String,
                    value: Utilities.getUuid(metaCode, it[0] as Long),
            )
        }
    }

    @SuppressWarnings('GrMethodMayBeStatic')
    Long getFilterOptionsTotalCount(
            String metaCode,
            Boolean withFolders,
            Boolean isFlat,
            Boolean folder,
            String search
    ) {
        def qpPair = QueryBuilderService.FilterOptions.getCountQueryAndParams(metaCode, withFolders, isFlat, folder, search)
        def query = api.db.query(qpPair.query).set(qpPair.params)
        return query.list().last() as Long
    }

    @SuppressWarnings('GrMethodMayBeStatic')
    List<Dto.Element> getElementsFlatList(
            String metaCode,
            Dto.Filter filter,
            Order.By orderBy,
            Order.Direction orderDirection,
            Integer offset,
            Integer limit
    ) {
        def qpPair = QueryBuilderService.FlatList.getSelectQueryAndParams(metaCode, filter, orderBy, orderDirection)
        def query = api.db.query(qpPair.query).set(qpPair.params)
        query.setFirstResult(offset)
        query.setMaxResults(limit)
        List<Dto.Element> dtos = query.list().collect { createDtoForFlatList(it as Object[], metaCode) } as List<Dto.Element>
        List<Dto.FileInfo> fileInfos = getFileInfos(dtos.collect { it.uuid })
        fileInfos.each { fileInfo ->
            Dto.Element dto = dtos.find { it.uuid == fileInfo.elementUuid }
            if (dto) dto.fileUuid = Utilities.getUuid('file', fileInfo.fileId)
        }
        return dtos
    }



    static Boolean isSearched(Dto.Element element, Dto.Filter filter) {
        Boolean isSearched = false
        if (filter.code && !isSearched) isSearched = element.code.toLowerCase().contains(filter.code.toLowerCase())
        if (filter.title && !isSearched) isSearched = element.title.toLowerCase().contains(filter.title.toLowerCase())
        if (filter.removed && !isSearched) isSearched = element.removed == filter.removed
        if (filter.folder && !isSearched) isSearched = element.folder == filter.folder
        return isSearched
    }

    static Boolean processElement(Dto.Element element, Dto.Filter filter) {
        element.children = element.children.findAll {
            it.isSearched = isSearched(it, filter) || processElement(it, filter)
            return it.isSearched
        }
        if (element.children.size() > 0) return true
        else false
    }


    @SuppressWarnings('GrMethodMayBeStatic')
    List<Dto.Element> getElementsHierarchyList(
            String metaCode,
            Dto.Filter filter,
            Order.By orderBy,
            Order.Direction orderDirection,
            Integer offset,
            Integer limit
    ) {
        def rootQpPair = QueryBuilderService.HierarchyList.getSelectQueryAndParamsForRoot(metaCode, filter, orderBy, orderDirection)
        def rootQuery = api.db.query(rootQpPair.query).set(rootQpPair.params)
        rootQuery.setFirstResult(offset)
        rootQuery.setMaxResults(limit)
        List<Dto.Element> firstLevel = rootQuery.list().collect { createDtoForHierarchyList(it as Object[], metaCode) } as List<Dto.Element>
        def childQpPair = QueryBuilderService.HierarchyList.getSelectQueryAndParamsForChildren(metaCode, firstLevel.collect { it.key })
        def childQuery = api.db.query(childQpPair.query).set(childQpPair.params)
        List<Object[]> otherLevels = childQuery.list() as List<Object[]>
        firstLevel.each { findAndAddNestedHierarchyElements(metaCode, it, otherLevels) }
        if (filter.code != null || filter.removed != null || filter.folder != null || filter.title != null) firstLevel.each { processElement(it, filter) }
        return firstLevel
    }

    private void findAndAddNestedHierarchyElements(String metaCode, Dto.Element el, List<Object[]> arr) {
        Long id = el.key
        List<Object[]> nested = arr.findAll { it[6] == id }
        nested.each {
            Dto.Element newEl = createDtoForHierarchyList(it, metaCode)
            if (el.children == null) el.children = [newEl]
            else el.children.add(newEl)
            findAndAddNestedHierarchyElements(metaCode, newEl, arr)
        }
    }

    @SuppressWarnings('GrMethodMayBeStatic')
    Integer getTotalCountForFlatList(String metaCode, Dto.Filter filter) {
        def qpPair = QueryBuilderService.FlatList.getCountQueryAndParams(metaCode, filter)
        return api.db.query(qpPair.query).set(qpPair.params).list().last() as Integer
    }

    @SuppressWarnings('GrMethodMayBeStatic')
    Integer getTotalCountForHierarchyList(String metaCode, Dto.Filter filter) {
        def qpPair = QueryBuilderService.HierarchyList.getCountQueryAndParams(metaCode, filter)
        return api.db.query(qpPair.query).set(qpPair.params).list().last() as Integer
    }

    @SuppressWarnings('GrMethodMayBeStatic')
    List<Dto.Element> getElementsForExport(
            String metaCode,
            Dto.Filter filter,
            Order.By orderBy,
            Order.Direction orderDirection,
            Integer offset,
            Integer limit
    ) {
        SpringContext springContext = SpringContext.getInstance()
        SessionFactory sessionFactory = springContext.getBean('sessionFactory', SessionFactory)
        Session session = sessionFactory.getCurrentSession()
        def qpPair = QueryBuilderService.FlatList.getSelectQueryAndParams(metaCode, filter, orderBy, orderDirection)
        Query query = session.createQuery(qpPair.query, Object[])
        query.setMaxResults(limit)
        query.setFirstResult(offset)
        query.setFetchSize(100)
        qpPair.params.each { String key, Object value ->
            if (value instanceof Collection) query.setParameterList(key, value)
            else query.setParameter(key, value)
        }
        List<Dto.Element> result = []
        try (ScrollableResults scroll = query.scroll(ScrollMode.FORWARD_ONLY)) {
            while (scroll.next()) {
                result.add(createDtoForFlatList(scroll.get(), metaCode))
            }
        }
        return result
    }
}

/** Класс для работы с xlsx документами */
class WorkbookService {

    /**
     * Установить значения в строку
     * @param row строка
     * @param values массив значений, установка будет происходит по сопоставлению индекса значения с индексом столбца
     * @return обновленная строка
     */
    static Row setValues(Row row, List<String> values) {
        values.eachWithIndex { it, index ->
            Cell cell = row.getCell(index) ?: row.createCell(index)
            cell.setCellValue(it)
        }
        return row
    }

    /**
     * Создать xlsx документ из данных, переданных в виде массива ДТО ОП
     * @param elements массив ДТО
     * @param isFlat справочник плоский
     * @param isFlat справочник с папками
     * @return xlsx документ
     */
    static Workbook createWorkbook(List<Dto.Element> elements, Boolean isFlat, Boolean isWithFolders) {
        Workbook workbook = new XSSFWorkbook()
        Sheet sheet = workbook.createSheet()
        Row headRow = sheet.createRow(0)
        List<String> headRowValues = [
                "В архиве",
                "Код",
                "Наименование",
                "Цвет",
                "Является папкой"
        ]
        if (!isFlat || isWithFolders) headRowValues.addAll([
                "Родитель в архиве",
                "Код родителя",
                "Наименование родителя",
                "Цвет родителя",
                "Родитель является папкой"
        ])
        setValues(headRow, headRowValues)
        Integer newRowIterator = 1
        elements.each { Dto.Element el ->
            List<String> values = []
            values.add(el.removed ? "Да" : "Нет")
            values.add(el.code)
            values.add(el.title)
            values.add(el.color)
            values.add(el.folder ? "Да" : "Нет")
            if (el.parent) {
                values.add(el.parent.removed ? "Да" : "Нет")
                values.add(el.parent.code)
                values.add(el.parent.title)
                values.add(el.parent.color)
                values.add(el.parent.folder ? "Да" : "Нет")
            }
            Row row = sheet.createRow(newRowIterator)
            setValues(row, values)
            newRowIterator++
        }
        return workbook
    }
}

@SuppressWarnings(['unused', 'GrMethodMayBeStatic'])
void getPage(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, Utilities.prefs.copy().assertHttpMethod('POST')).process { WebApiUtilities webUtils ->
        Dto.PageParams body = webUtils.getBodyAsJsonElseThrow(Dto.PageParams.class)
        String metaCode = webUtils.getParamElseThrow("metaCode")
        Order.By orderBy = Optional.ofNullable(body.sorter?.columnKey).orElse(Order.By.TITLE)
        Order.Direction orderDirection = Optional.ofNullable(body.sorter?.order).orElse(Order.Direction.DESC)
        Integer page = Optional.ofNullable(body.pager?.current).orElse(1)
        Integer pageSize = Optional.ofNullable(body.pager?.pageSize).orElse(20)
        if (page < 1) throw new WebApiException.BadRequest("Страница меньше 1")
        if (pageSize !in Constants.PAGE_SIZES) throw new WebApiException.BadRequest("Размер страницы не в списке разрешенных")
        DataService dataService = new DataService()
        List<Dto.Element> elements
        TableMode mode = body.preferences.tableMode
        if (!api.metainfo.getMetaClass(metaCode)) throw new WebApiException.BadRequest("Метакласса с кодом ${metaCode} не существует")
        Integer total
        switch (mode) {
            case TableMode.FLAT -> {
                elements = dataService.getElementsFlatList(
                        metaCode,
                        body.filter,
                        orderBy,
                        orderDirection,
                        (page - 1) * pageSize,
                        pageSize
                )
                total = dataService.getTotalCountForFlatList(metaCode, body.filter)
            }
            case TableMode.HIERARCHY -> {
                elements = dataService.getElementsHierarchyList(
                        metaCode,
                        body.filter,
                        orderBy,
                        orderDirection,
                        (page - 1) * pageSize,
                        pageSize
                )
                total = dataService.getTotalCountForHierarchyList(metaCode, body.filter)
            }
        }
        webUtils.setBodyAsJson(
                new Dto.Page(
                        pager: new Dto.Pager(current: page, pageSize: pageSize, total: total),
                        sorter: new Dto.Sorter(order: orderDirection, columnKey: orderBy),
                        preferences: body.preferences,
                        elements: elements,
                        filter: body.filter,
                )
        )
    }
}

@SuppressWarnings(['unused', 'GrMethodMayBeStatic'])
void getSelectOptionsPage(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, Utilities.prefs.copy().assertHttpMethod('GET')).process { WebApiUtilities webUtils ->
        String metaCode = webUtils.getParamElseThrow("metaCode")
        Boolean folder = webUtils.getParamElseThrow("folder", Boolean)
        Utilities.logInfo("getSelectOptionsPage: folder: " + folder)
        String search = webUtils.getParam("search").orElse(null)
        Integer page = webUtils.getParam("page", Integer).orElse(1)
        Integer pageSize = webUtils.getParam("pageSize", Integer).orElse(20)

        if (page < 1) throw new WebApiException.BadRequest("Страница меньше 1")
        if (pageSize !in Constants.PAGE_SIZES) throw new WebApiException.BadRequest("Размер страницы не в списке разрешенных")
        DataService dataService = new DataService()
        if (!api.metainfo.getMetaClass(metaCode)) throw new WebApiException.BadRequest("Метакласса с кодом ${metaCode} не существует")
        SpringContext springContext = SpringContext.getInstance()
        MetainfoService metainfoService = springContext.getBean('metainfoService', MetainfoService)
        Catalog catalog = metainfoService.getCatalog(metaCode)
        webUtils.setBodyAsJson(
                new Dto.SelectOptionsPage(
                        pager: new Dto.Pager(
                                current: page,
                                pageSize: pageSize,
                                total: dataService.getFilterOptionsTotalCount(
                                        metaCode,
                                        catalog.isWithFolders(),
                                        catalog.isFlat(),
                                        folder,
                                        search
                                )
                        ),
                        options: dataService.getFilterOptions(
                                metaCode,
                                catalog.isWithFolders(),
                                catalog.isFlat(),
                                folder,
                                (page - 1) * pageSize,
                                pageSize,
                                search
                        )
                )
        )
    }
}

@SuppressWarnings(['unused', 'GrMethodMayBeStatic'])
void getInitialData(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, Utilities.prefs.copy().assertHttpMethod('GET')).process { WebApiUtilities webUtils ->
        SpringContext springContext = SpringContext.getInstance()
        MetainfoService metainfoService = springContext.getBean('metainfoService', MetainfoService)
        List<Dto.CatalogType> arr = []
        api.metainfo.getMetaClass('catalogItem').children.each {
            if (it.abstract) return
            Catalog catalog = metainfoService.getCatalog(it.code)
            if (catalog) arr.add(new Dto.CatalogType(label: catalog.title, value: catalog.code, isFlat: catalog.isFlat(), isWithFolders: catalog.isWithFolders()))
        }
        metainfoService.getCatalogCodes()
        webUtils.setBodyAsJson(
                new Dto.InitialData(
                        licenced: Utilities.isUserLicensed(user),
                        exportFileLimit: Constants.EXPORT_FILE_LIMIT,
                        types: arr,
                        //TODO
                        isAdmin: false
                )
        )
    }
}

@SuppressWarnings(['unused', 'GrMethodMayBeStatic'])
void delete(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, Utilities.prefs.copy().assertHttpMethod('GET')).process { WebApiUtilities webUtils ->
        String uuid = webUtils.getParamElseThrow('uuid')
        ISDtObject object = utils.get(uuid)
        if (!object) throw new WebApiException.BadRequest("Объект не найден")
        utils.delete(object)
    }
}

@SuppressWarnings(['unused', 'GrMethodMayBeStatic'])
void edit(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, Utilities.prefs.copy().assertHttpMethod('GET')).process { WebApiUtilities webUtils ->
        String uuid = webUtils.getParamElseThrow('uuid')
        ISDtObject object = utils.get(uuid)
        if (!object) throw new WebApiException.BadRequest("Объект не найден")
        String title = webUtils.getParam('title').orElse(null)
        Boolean removed = webUtils.getParam('removed', Boolean).orElse(null)
        String parentUuid = webUtils.getParam('parent').orElse(null)
        String color = webUtils.getParam('color').orElse(null)
        Map<String, Object> attrs = [
                'title'  : title,
                'removed': removed,
                'color'  : color,
                'parent' : parentUuid
        ] as Map<String, Object>
        webUtils.setBodyAsJson(DataService.createDtoFromISDtObject(utils.edit(object, attrs)))
    }
}

@SuppressWarnings(['unused', 'GrMethodMayBeStatic'])
void create(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, Utilities.prefs.copy().assertHttpMethod('GET')).process { WebApiUtilities webUtils ->
        String metaCode = webUtils.getParamElseThrow('metaCode')
        String title = webUtils.getParamElseThrow('title')
        String code = webUtils.getParamElseThrow('code')
        Boolean folder = webUtils.getParam('folder', Boolean).orElse(false)
        String color = webUtils.getParam('color').orElse(null)
        Map<String, Object> attrs = [
                'title' : title,
                'code'  : code,
                'folder': folder,
                'color' : color
        ] as Map<String, Object>
        String parentUuid = webUtils.getParam('parent').orElse(null)
        if (parentUuid) {
            ISDtObject parent = utils.get(parentUuid)
            if (!parent) throw new WebApiException.BadRequest("Родитель не найден")
            attrs.put('parent', parent)
        }
        webUtils.setBodyAsJson(DataService.createDtoFromISDtObject(utils.create(metaCode, attrs)))
    }
}

@SuppressWarnings(['unused', 'GrMethodMayBeStatic'])
void get(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, Utilities.prefs.copy().assertHttpMethod('GET')).process { WebApiUtilities webUtils ->
        String uuid = webUtils.getParamElseThrow('uuid')
        ISDtObject object = utils.get(uuid)
        if (!object) throw new WebApiException.BadRequest("Объект не найден")
        webUtils.setBodyAsJson(DataService.createDtoFromISDtObject(object))
    }
}

/**
 * POST
 * Получить файл для экспорта
 */
@SuppressWarnings(['unused', 'GrMethodMayBeStatic'])
void getExportFile(HttpServletRequest request, HttpServletResponse response, ISDtObject user) {
    RequestProcessor.create(request, response, user, Utilities.prefs.copy().assertHttpMethod('POST')).process { WebApiUtilities webUtils ->
        Order.By DEFAULT_ORDER_BY = Order.By.TITLE
        Order.Direction DEFAULT_ORDER_DIRECTION = Order.Direction.ASC
        String metaCode = webUtils.getParamElseThrow('metaCode')
        Dto.ExportParams params = webUtils.getBodyAsJsonElseThrow(Dto.ExportParams.class)
        Order.By orderBy = params.sorter?.columnKey
        Order.Direction orderDirection = params.sorter?.order
        if (!orderDirection || !orderBy) {
            orderBy = DEFAULT_ORDER_BY
            orderDirection = DEFAULT_ORDER_DIRECTION
        }
        Integer offset = params.offset ?: 0
        Integer limit = params.limit ?: Constants.EXPORT_FILE_LIMIT
        if (limit > Constants.EXPORT_FILE_LIMIT) throw new WebApiException.BadRequest("Превышен максимальный размер файла для экспорта: ${Constants.EXPORT_FILE_LIMIT}")
        DataService dataService = new DataService()
        List<Dto.Element> els = dataService.getElementsForExport(
                metaCode,
                params.filter,
                orderBy,
                orderDirection,
                offset,
                limit
        )
        SpringContext springContext = SpringContext.getInstance()
        MetainfoService metainfoService = springContext.getBean('metainfoService', MetainfoService)
        Catalog catalog = metainfoService.getCatalog(metaCode)
        Workbook workbook = WorkbookService.createWorkbook(els, catalog.isFlat(), catalog.isWithFolders())
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()
        workbook.write(byteArrayOutputStream)
        String ct = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
        webUtils.setBodyAsBytes(byteArrayOutputStream.toByteArray(), ct)
        response.addHeader("Content-Disposition", "attachment; filename=\"export.xlsx\"")
    }
}
