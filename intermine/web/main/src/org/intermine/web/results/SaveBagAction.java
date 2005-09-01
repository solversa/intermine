package org.intermine.web.results;

/*
 * Copyright (C) 2002-2005 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.log4j.Logger;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.intermine.metadata.ClassDescriptor;
import org.intermine.model.InterMineObject;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.query.Results;
import org.intermine.web.Constants;
import org.intermine.web.InterMineAction;
import org.intermine.web.Profile;
import org.intermine.web.SessionMethods;
import org.intermine.web.WebUtil;
import org.intermine.web.bag.InterMineBag;
import org.intermine.web.bag.InterMineIdBag;
import org.intermine.web.bag.InterMinePrimitiveBag;

/**
 * Saves selected items in a new bag or combines with existing bag.
 *
 * @author Andrew Varley
 * @author Thomas Riley
 * @author Kim Rutherford
 */
public class SaveBagAction extends InterMineAction
{
    protected static final Logger LOG = Logger.getLogger(SaveBagAction.class);

    /**
     * @param mapping The ActionMapping used to select this instance
     * @param form The optional ActionForm bean for this request (if any)
     * @param request The HTTP request we are processing
     * @param response The HTTP response we are creating
     * @return an ActionForward object defining where control goes next
     * @exception Exception if the application business logic throws
     *  an exception
     */
    public ActionForward execute(ActionMapping mapping,
                                 ActionForm form,
                                 HttpServletRequest request,
                                 HttpServletResponse response)
        throws Exception {

        if (request.getParameter("saveNewBag") != null) {
            return saveBag(mapping, form, request, response);
        } else if (request.getParameter("addToExistingBag") != null) {
            return saveBag(mapping, form, request, response);
        } else {
            // the form was submitted without pressing a submit button, eg. using submit() from
            // Javascript
        }

        return null;
    }

    /**
     * Save the selected objects to a bag on the session
     * @param mapping The ActionMapping used to select this instance
     * @param form The optional ActionForm bean for this request (if any)
     * @param request The HTTP request we are processing
     * @param response The HTTP response we are creating
     * @return an ActionForward object defining where control goes next
     * @exception ServletException if a servlet error occurs
     */
    public ActionForward saveBag(ActionMapping mapping,
                                 ActionForm form,
                                 HttpServletRequest request,
                                 HttpServletResponse response)
        throws ServletException {
        HttpSession session = request.getSession();
        ServletContext servletContext = session.getServletContext();
        Profile profile = (Profile) session.getAttribute(Constants.PROFILE);
        //PagedTable pt = (PagedTable) session.getAttribute(Constants.RESULTS_TABLE);
        PagedTable pt = SessionMethods.getResultsTable(session, request.getParameter("table"));
        SaveBagForm crf = (SaveBagForm) form;

        InterMineBag bag = null;
        boolean storingIds = false;
        int defaultMax = 10000;
        int maxBagSize = WebUtil.getIntSessionProperty(session, "max.bag.size", defaultMax);

        // Create the right kind of bag
        String selected = crf.getSelectedObjects()[0];
        int index = selected.indexOf(",");
        int col = Integer.parseInt(index == -1 ? selected : selected.substring(0, index));
        Object type = ((Column) pt.getColumns().get(col)).getType();

        if (type instanceof ClassDescriptor) {
            bag = new InterMineIdBag();
            storingIds = true;
        } else {
            bag = new InterMinePrimitiveBag();
        }

        List allRows = pt.getAllRows();

        if (allRows instanceof Results) {
            Results results = (Results) allRows;
            
            if (results.size() > maxBagSize) {
                ActionMessage actionMessage =
                    new ActionMessage("bag.tooBig", new Integer(maxBagSize));
                recordError(actionMessage, request);
                
                return mapping.findForward("results");
            }
            
            try {
                // make a copy of the Results object with a larger batch size so the object
                // store doesn't need to do lots of queries
                // we copy because setBatchSize() throws an exception if size() has already
                // been called
                Results newResults = results.getObjectStore().execute(results.getQuery());
                
                if (maxBagSize > results.getObjectStore().getMaxLimit()) {
                    newResults.setBatchSize(results.getObjectStore().getMaxLimit());
                } else {
                    newResults.setBatchSize(maxBagSize);
                }
                
                // make sure we can get the first batch
                try {
                    newResults.get(0);
                } catch (IndexOutOfBoundsException e) {
                    // Ignore - that means there are NO rows in this results object.
                }
                
                allRows = newResults;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof ObjectStoreException) {
                    recordError(new ActionMessage("errors.query.objectstoreerror"),
                                request, (ObjectStoreException) e.getCause(), LOG);
                    return mapping.findForward("results");
                }
                throw e;
            } catch (ObjectStoreException e) {
                recordError(new ActionMessage("errors.query.objectstoreerror"),
                            request, e, LOG);
                return mapping.findForward("results");
            }
        }

        // save selected columns first
        for (Iterator rowIterator = allRows.iterator(); rowIterator.hasNext();) {
            List thisRow = (List) rowIterator.next();

            // go through the selected items (checkboxes) and add to the bag-to-save
            for (Iterator itemIterator = Arrays.asList(crf.getSelectedObjects()).iterator();
                 itemIterator.hasNext();) {
                String selectedObject = (String) itemIterator.next();
                // selectedObject is of the form "column,row" or "column"
                int commaIndex = selectedObject.indexOf(",");
                if (commaIndex == -1) {
                    int column = Integer.parseInt(selectedObject);
                    
                    if (storingIds) {
                        Integer id = ((InterMineObject) thisRow.get(column)).getId();
                        bag.add(id);
                    } else {
                        bag.add(thisRow.get(column));
                    }

                    if (bag.size() > maxBagSize) {
                        ActionMessage actionMessage =
                            new ActionMessage("bag.tooBig", new Integer(maxBagSize));
                        recordError(actionMessage, request);
                
                        return mapping.findForward("results");
                    }
                }
            }
        }

        // not save individually selected items
        for (Iterator itemIterator = Arrays.asList(crf.getSelectedObjects()).iterator();
             itemIterator.hasNext();) {
            String selectedObject = (String) itemIterator.next();
            // selectedObject is of the form "column,row" or "column"
            int commaIndex = selectedObject.indexOf(",");
            if (commaIndex != -1) {
                // use the column,row to pick out the object from PagedTable
                int column = Integer.parseInt(selectedObject.substring(0, commaIndex));
                int row = Integer.parseInt(selectedObject.substring(commaIndex + 1));
                Object value = ((List) pt.getRows().get(row)).get(column);
                if (storingIds) {
                    Integer id = ((InterMineObject) value).getId();
                    bag.add(id);
                } else {
                    bag.add(value);
                }
                if (bag.size() > maxBagSize) {
                    ActionMessage actionMessage =
                        new ActionMessage("bag.tooBig", new Integer(maxBagSize));
                    recordError(actionMessage, request);

                    return mapping.findForward("results");
                }
            }
        }

        String bagName;
        if (request.getParameter("saveNewBag") != null) {
            bagName = crf.getNewBagName();
        } else {
            bagName = crf.getExistingBagName();
        }
        InterMineBag existingBag = (InterMineBag) profile.getSavedBags().get(bagName);
        if (existingBag != null) {
            if (!existingBag.getClass().equals(bag.getClass())) {
                recordError(new ActionMessage("bag.typesDontMatch"), request);
                return mapping.findForward("results");
            }
            bag.addAll(existingBag);
            SessionMethods.invalidateBagTable(session, bagName);
        }
        if (bag.size() > maxBagSize) {
            ActionMessage actionMessage =
                new ActionMessage("bag.tooBig", new Integer(maxBagSize));
            recordError(actionMessage, request);

            return mapping.findForward("results");
        }
        profile.saveBag(bagName, bag);

        recordMessage(new ActionMessage("bag.saved", bagName), request);

        return mapping.findForward("results");
    }
}
