package rundeck.controllers

import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.common.FrameworkProject
import com.dtolabs.rundeck.server.authorization.AuthConstants
import rundeck.filters.ApiRequestFilters
import rundeck.services.ProjectServiceException

import javax.servlet.http.HttpServletResponse
import java.text.SimpleDateFormat
import org.apache.commons.fileupload.util.Streams
import org.springframework.web.multipart.MultipartHttpServletRequest
import java.util.zip.ZipInputStream
import com.dtolabs.rundeck.core.authentication.Group

class ProjectController extends ControllerBase{
    def frameworkService
    def projectService
    def apiService
    def static allowedMethods = [
            importArchive: ['POST'],
            delete: ['POST'],
    ]

    def index () {
        return redirect(controller: 'menu', action: 'jobs')
    }

    def export={
        def project=params.project?:params.name
        if (!project){
            return renderErrorView("Project parameter is required")
        }
        Framework framework = frameworkService.getRundeckFramework()
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (notFoundResponse(frameworkService.existsFrameworkProject(project), 'Project', project)) {
            return
        }

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAll(authContext, [type: 'project', name: project],
                        [AuthConstants.ACTION_ADMIN]),
                AuthConstants.ACTION_ADMIN, 'Project',project)) {
            return
        }
        def project1 = frameworkService.getFrameworkProject(project)

        //temp file
        def outfile
        try {
            outfile = projectService.exportProjectToFile(project1,framework)
        } catch (ProjectServiceException exc) {
            return renderErrorView(exc.message)
        }
        SimpleDateFormat dateFormater = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        def dateStamp = dateFormater.format(new Date());
        //output the file as an attachment
        response.setContentType("application/zip")
        response.setHeader("Content-Disposition", "attachment; filename=\"${project}-${dateStamp}.rdproject.jar\"")

        outfile.withInputStream {instream->
            Streams.copy(instream,response.outputStream,false)
        }
        outfile.delete()
    }

    def importArchive={
        def project = params.project?:params.name
        if (!project) {
            return renderErrorView("Project parameter is required")
        }
        Framework framework = frameworkService.getRundeckFramework()
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (notFoundResponse(frameworkService.existsFrameworkProject(project), 'Project', project)) {
            return
        }

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAll(authContext, [type: 'project', name: project],
                        [AuthConstants.ACTION_ADMIN]),
                AuthConstants.ACTION_ADMIN, 'Project', project)) {
            return
        }

        def project1 = frameworkService.getFrameworkProject(project)

        //uploaded file
        if (request instanceof MultipartHttpServletRequest) {
            def file = request.getFile("zipFile")
            if (!file || file.empty) {
                flash.message = "No file was uploaded."
                return
            }
            String roleList = request.subject.getPrincipals(Group.class).collect {it.name}.join(",")
            def result=projectService.importToProject(project1,session.user,roleList,framework,authContext,new ZipInputStream(file.getInputStream()),params.import)

            if(result.success){
                flash.message="Archive successfully imported"
            }else{
                flash.error="Failed to import some jobs"
                flash.joberrors=result.joberrors
            }
            return redirect(controller: 'menu',action: 'admin',params:[project:project])
        }
    }

    def delete = {
        def project = params.project
        if (!project) {
            request.error = "Project parameter is required"
            return render(view: "/common/error")
        }
        Framework framework = frameworkService.getRundeckFramework()
        if (!frameworkService.existsFrameworkProject(project)) {
            response.setStatus(404)
            request.error = g.message(code: 'scheduledExecution.project.invalid.message', args: [project])
            return render(view: "/common/error")
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!frameworkService.authorizeApplicationResourceAll(authContext, [type: 'project', name: project],
                [AuthConstants.ACTION_DELETE])) {
            response.setStatus(403)
            request.error = g.message(code: 'api.error.item.unauthorized', args: [AuthConstants.ACTION_DELETE,
                    "Project", params.project])
            return render(view: "/common/error")
        }
        def project1 = frameworkService.getFrameworkProject(project)

        def result = projectService.deleteProject(project1, framework)
        if (!result.success) {
            log.error("Failed to delete project: ${result.error}")
            flash.error = result.error
            return redirect(controller: 'menu', action: 'admin', params: [project: project])
        }
        flash.message = 'Deleted project: ' + project
        return redirect(controller: 'menu', action: 'home')
    }

    /**
     * Render project XML result using a builder
     * @param pject framework project object
     * @param delegate builder delegate for response
     * @param hasConfigAuth true if 'configure' action is allowed
     * @param vers api version requested
     */
    private def renderApiProjectXml (FrameworkProject pject, delegate, hasConfigAuth=false, vers=1){
        delegate.'project'(href: generateProjectApiUrl(pject.name)) {
            name(pject.name)
            description(pject.hasProperty('project.description') ? pject.getProperty('project.description') : '')
            if (vers < ApiRequestFilters.V11) {
                if (pject.hasProperty("project.resources.url")) {
                    resources {
                        providerURL(pject.getProperty("project.resources.url"))
                    }
                }
            } else if (hasConfigAuth) {
                //include config data
                renderApiProjectConfigXml(pject,delegate)
            }
        }
    }
    /**
     * Render project config XML content using a builder
     * @param pject framework project object
     * @param delegate builder delegate for response
     */
    private def renderApiProjectConfigXml (FrameworkProject pject, delegate){
        delegate.'config' {
            frameworkService.loadProjectProperties(pject).each { k, v ->
                delegate.'property'(key: k, value: v)
            }
        }
    }

    /**
     * Render project JSON result using a builder
     * @param pject framework project object
     * @param delegate builder delegate for response
     * @param hasConfigAuth true if 'configure' action is allowed
     * @param vers api version requested
     */
    private def renderApiProjectJson (FrameworkProject pject, delegate, hasConfigAuth=false, vers=1){
        delegate.href= generateProjectApiUrl(pject.name)
        delegate.name=pject.name
        delegate.description=pject.hasProperty('project.description') ? pject.getProperty('project.description') : ''
        if(hasConfigAuth){
            delegate.config {
                renderApiProjectConfigJson(pject,delegate)
            }
        }
    }

    /**
     * Render project config JSON content using a builder
     * @param pject framework project object
     * @param delegate builder delegate for response
     */
    private def renderApiProjectConfigJson (FrameworkProject pject, delegate){
        frameworkService.loadProjectProperties(pject).each { k, v ->
            delegate."${k}" = v
        }
    }


    /**
     * Generate absolute api URL for the project
     * @param projectName
     * @return
     */
    private String generateProjectApiUrl(String projectName) {
        g.createLink(absolute: true, uri: "/api/${ApiRequestFilters.API_CURRENT_VERSION}/project/${projectName}")
    }

    /**
     * API: /api/11/projects
     */
    def apiProjectList(){
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        def projlist = frameworkService.projects(authContext)
        withFormat{

            xml{
                return apiService.renderSuccessXml(response) {
                    delegate.'projects'(count: projlist.size()) {
                        projlist.sort { a, b -> a.name <=> b.name }.each { pject ->
                            //don't include config data
                            renderApiProjectXml(pject, delegate, false, request.api_version)
                        }
                    }
                }
            }
            json{
                return render(contentType: 'application/json'){
                    projects=array{
                        def builder = delegate
                        projlist.sort { a, b -> a.name <=> b.name }.each { pject ->
                            //don't include config data
                            project{
                                renderApiProjectJson(pject, builder, false, request.api_version)
                            }
                        }
                    }
                }
            }
        }

    }

    /**
     * API: /api/11/project/NAME
     */
    def apiProjectGet(){
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!params.project) {
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.parameter.required', args: ['project']])
        }
        if (!frameworkService.authorizeApplicationResourceAll(authContext, [type: 'project', name: params.project],
                [AuthConstants.ACTION_READ])) {
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized', args: ['Read', 'Project', params.project]])
        }
        def exists = frameworkService.existsFrameworkProject(params.project)
        if (!exists) {
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist', args: ['project', params.project]])
        }
        def configAuth= frameworkService.authorizeApplicationResourceAll(authContext, [type: 'project',
                name: params.project], [AuthConstants.ACTION_CONFIGURE])
        def pject = frameworkService.getFrameworkProject(params.project)
        withFormat{
            xml{

                return apiService.renderSuccessXml(response) {
                    if(request.api_version<ApiRequestFilters.V11){
                        delegate.'projects'(count: 1) {
                            renderApiProjectXml(pject, delegate, configAuth, request.api_version)
                        }
                    }else{
                        renderApiProjectXml(pject, delegate, configAuth, request.api_version)
                    }
                }
            }
            json{
                return render(contentType: 'application/json'){
                    renderApiProjectJson(pject, delegate, configAuth, request.api_version)
                }
            }
        }
    }


    def apiProjectCreate() {
        if (!apiService.requireVersion(request, response, ApiRequestFilters.V11)) {
            return
        }
        //allow Accept: header, but default to the request format
        def respFormat = apiService.extractResponseFormat(request,response,['xml','json'])
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!frameworkService.authorizeApplicationResourceTypeAll(authContext, 'project',
                [AuthConstants.ACTION_CREATE])) {
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_FORBIDDEN,
                            code: "api.error.item.unauthorized",
                            args: [AuthConstants.ACTION_CREATE, "Rundeck", "Project"],
                            format:respFormat
                    ])
        }

        def project = null
        Map config = null

        //parse request format
        def succeeded = apiService.parseJsonXmlWith(request,response, [
                xml: { xml ->
                    project = xml?.name[0]?.text()
                    config = [:]
                    xml?.config?.property?.each {
                        config[it.'@key'.text()] = it.'@value'.text()
                    }
                },
                json: { json ->
                    project = json?.name?.toString()
                    config = json?.config
                }
        ])
        if(!succeeded){
            return
        }

        if (!project) {
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.invalid.request",
                            args: ["Project 'name' is required"],
                            format: respFormat
                    ])
        }
        def proj
        def errors
        (proj,errors)=frameworkService.createFrameworkProject(project,new Properties(config))
        if(errors){
            return apiService.renderErrorFormat(response,[status:HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message: errors.join('; '),format: respFormat])
        }
        switch(respFormat) {
            case 'xml':
                render {
                    renderApiProjectXml(proj,delegate,true,request.api_version)
                }
                break
            case 'json':
                render(contentType: 'application/json') {
                    renderApiProjectJson(proj, delegate, true, request.api_version)
                }
                break
        }
    }

    def apiProjectDelete(){
        if (!apiService.requireVersion(request, response, ApiRequestFilters.V11)) {
            return
        }
        String project = params.project
        if (!project) {
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.parameter.required",
                            args: ['project']
                    ])
        }
        Framework framework = frameworkService.getRundeckFramework()
        if (!frameworkService.existsFrameworkProject(project)) {

            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_NOT_FOUND,
                            code: "api.error.item.doesnotexist",
                            args: ['Project',project]
                    ])
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (!frameworkService.authorizeApplicationResourceAll(authContext,
                [type: 'project', name: project], [AuthConstants.ACTION_DELETE])) {
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_FORBIDDEN,
                            code: "api.error.item.unauthorized",
                            args: [AuthConstants.ACTION_DELETE, "Project",project]
                    ])
        }
        def project1 = frameworkService.getFrameworkProject(project)

        def result = projectService.deleteProject(project1, framework)
        if (!result.success) {
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                            message: result.error,
                    ])
        }
        //success
        response.status=HttpServletResponse.SC_NO_CONTENT
    }
    /**
     * support project/NAME/config endpoint GET and PUT: validate project and configure authorization
     * @return FrameworkProject for the project
     */
    private FrameworkProject apiProjectConfigSetup(){
        if (!apiService.requireVersion(request, response, ApiRequestFilters.V11)) {
            return
        }
        String project = params.project
        if (!project) {
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.parameter.required",
                            args: ['project']
                    ])
        }
        if (!frameworkService.existsFrameworkProject(project)) {

            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_NOT_FOUND,
                            code: "api.error.item.doesnotexist",
                            args: ['Project', project]
                    ])
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (!frameworkService.authorizeApplicationResourceAll(authContext,
                [type: 'project', name: project], [AuthConstants.ACTION_CONFIGURE])) {
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_FORBIDDEN,
                            code: "api.error.item.unauthorized",
                            args: [AuthConstants.ACTION_CONFIGURE, "Project", project]
                    ])
        }
        return frameworkService.getFrameworkProject(project)
    }
    def apiProjectConfigGet(){
        def proj=apiProjectConfigSetup()
        //render project config only

        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json'], 'xml')
        switch (respFormat) {
            case 'xml':
                render {
                    renderApiProjectConfigXml(proj, delegate)
                }
                break
            case 'json':
                render(contentType: 'application/json') {
                    renderApiProjectConfigJson(proj, delegate)
                }
                break
        }
    }

    def apiProjectConfigPut() {
        def project = apiProjectConfigSetup()
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json'])
        //parse config data
        def config=null
        def succeed = apiService.parseJsonXmlWith(request, response, [
                xml: { xml ->
                    config = [:]
                    xml?.property?.each {
                        config[it.'@key'.text()] = it.'@value'.text()
                    }
                },
                json: { json ->
                    config = json
                }
        ])
        if(!succeed){
            return
        }
        def result=frameworkService.updateFrameworkProjectConfig(project.name,new Properties(config),null)
        if(!result.success){
            return apiService.renderErrorFormat(response,[
                    status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message:result.error,
                    format:respFormat
            ])
        }

        switch (respFormat) {
            case 'xml':
                render {
                    renderApiProjectConfigXml(project, delegate)
                }
                break
            case 'json':
                render(contentType: 'application/json') {
                    renderApiProjectConfigJson(project, delegate)
                }
                break
        }

    }
    def apiProjectConfigKeyGet() {
        def project = apiProjectConfigSetup()
        def key = params.keypath
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json','text'],'text')

        switch (respFormat) {
            case 'text':
                render (contentType: 'text/plain', text:project.getProperty(key))
                break
            case 'xml':
                render {
                    property(key:key,value:project.getProperty(key))
                }
                break
            case 'json':
                render(contentType: 'application/json') {
                    delegate.'key'=key
                    value= project.getProperty(key)
                }
                break
        }
    }
    def apiProjectConfigKeyPut() {
        def project = apiProjectConfigSetup()
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json'])


    }
    def apiProjectConfigKeyDelete() {
        def project = apiProjectConfigSetup()
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json'])


    }

}
