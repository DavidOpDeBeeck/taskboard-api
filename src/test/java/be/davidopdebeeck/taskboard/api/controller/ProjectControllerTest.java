package be.davidopdebeeck.taskboard.api.controller;

import be.davidopdebeeck.taskboard.api.application.Application;
import be.davidopdebeeck.taskboard.core.Lane;
import be.davidopdebeeck.taskboard.core.Project;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.boot.test.WebIntegrationTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.List;

import static org.junit.Assert.*;

@RunWith( SpringJUnit4ClassRunner.class )
@SpringApplicationConfiguration( classes = Application.class )
@WebIntegrationTest
public class ProjectControllerTest extends ControllerTest
{


    @Test
    public void testGetProjects()
    {
        Project p1 = new Project( "Test Project #1" );
        Project p2 = new Project( "Test Project #2" );
        Project p3 = new Project( "Test Project #3" );
        Project p4 = new Project( "Test Project #4" );

        projectDAO.create( p1 );
        projectDAO.create( p2 );
        projectDAO.create( p3 );
        projectDAO.create( p4 );

        ResponseEntity<List<Project>> apiResponse = restTemplate.exchange( url(), HttpMethod.GET, null, new ParameterizedTypeReference<List<Project>>()
        {} );

        assertNotNull( apiResponse );

        List<Project> projects = apiResponse.getBody();

        assertNotNull( projects );

        assertTrue( projects.contains( p1 ) );
        assertTrue( projects.contains( p2 ) );
        assertTrue( projects.contains( p3 ) );
        assertTrue( projects.contains( p4 ) );

        projectDAO.remove( p1 );
        projectDAO.remove( p2 );
        projectDAO.remove( p3 );
        projectDAO.remove( p4 );
    }

    @Test
    public void testAddProjects()
    {
        String title = "Test Project";

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType( MediaType.APPLICATION_FORM_URLENCODED );

        HttpEntity<String> httpEntity = new HttpEntity<>( "title=" + title, requestHeaders );
        Project apiResponse = restTemplate.postForObject( url(), httpEntity, Project.class );

        assertNotNull( apiResponse );

        Project project = projectDAO.getById( apiResponse.getId() );
        assertEquals( project.getId(), apiResponse.getId() );
        assertEquals( project.getTitle(), apiResponse.getTitle() );

        projectDAO.remove( project );
    }

    @Test
    public void testGetProjectById()
    {
        Project project = new Project( "Test Project" );
        String projectId = project.getId();

        projectDAO.create( project );

        Project apiResponse = restTemplate.getForObject( url() + "/" + projectId, Project.class );

        assertNotNull( apiResponse );
        assertEquals( project.getId(), apiResponse.getId() );
        assertEquals( project.getTitle(), apiResponse.getTitle() );

        projectDAO.remove( project );
    }

    @Test
    public void testRemoveProjectById()
    {
        Project project = new Project( "Test Project" );
        String projectId = project.getId();

        projectDAO.create( project );

        restTemplate.delete( url() + "/" + projectId );

        Project removedProject = projectDAO.getById( projectId );
        assertNull( removedProject );
    }

    @Test
    public void testAddLaneToProjectById()
    {
        String title = "To Verify";
        int sequence = 3;
        boolean completed = true;

        Project project = new Project( "Test Project" );

        projectDAO.create( project );

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType( MediaType.APPLICATION_FORM_URLENCODED );

        HttpEntity<String> httpEntity = new HttpEntity<>( "title=" + title + "&sequence=" + sequence + "&completed=" + completed, requestHeaders );
        Project apiResponse = restTemplate.postForObject( url() + "/" + project.getId() + "/lanes", httpEntity, Project.class );

        assertNotNull( apiResponse );
        
        Lane lane = apiResponse.getLanes()
                .iterator()
                .next();

        assertEquals( title, lane.getTitle() );
        assertEquals( sequence, lane.getSequence() );
        assertEquals( completed, lane.isCompleted() );

        projectDAO.remove( project );
    }

    @Override
    protected String context()
    {
        return "projects";
    }
}