package be.davidopdebeeck.taskboard.api.security;

import be.davidopdebeeck.taskboard.core.Project;
import be.davidopdebeeck.taskboard.dao.LaneDAO;
import be.davidopdebeeck.taskboard.dao.ProjectDAO;
import be.davidopdebeeck.taskboard.dao.TaskDAO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@Component
public class SecurityInterceptor implements HandlerInterceptor
{

    @Autowired
    ProjectDAO projectDAO;

    @Autowired
    LaneDAO laneDAO;

    @Autowired
    TaskDAO taskDAO;

    @Autowired
    SecurityManager securityManager;

    @Override
    public boolean preHandle( HttpServletRequest request, HttpServletResponse response, Object handler ) throws Exception
    {
        Map<String, String> pathVariables = (Map) request.getAttribute( HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE );

        if ( pathVariables != null )
        {
            String projectId = pathVariables.get( "projectId" );
            String laneId = pathVariables.get( "laneId" );
            String taskId = pathVariables.get( "taskId" );

            Project project = null;
            boolean secured = false;

            if ( projectId != null )
                project = projectDAO.getById( projectId );
            else if ( laneId != null )
                project = laneDAO.getProject( laneDAO.getById( laneId ) );
            else if ( taskId != null )
                project = taskDAO.getProject( taskDAO.getById( taskId ) );

            if ( project != null )
                secured = project.isSecured();

            if ( secured )
            {
                Cookie[] cookies = request.getCookies();

                if ( cookies == null )
                    return false;

                boolean passwordValid = false;

                for ( Cookie cookie : cookies )
                {
                    if ( cookie.getName().equals( project.getId() ) )
                    {
                        String password = cookie.getValue();

                        if ( securityManager.validate( project.getId(), password ) )
                        {
                            passwordValid = true;
                        }
                    }
                }

                return passwordValid;
            }

            return true;
        }

        return true;
    }

    @Override
    public void postHandle( HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView ) throws Exception
    {

    }

    @Override
    public void afterCompletion( HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex ) throws Exception
    {

    }
}