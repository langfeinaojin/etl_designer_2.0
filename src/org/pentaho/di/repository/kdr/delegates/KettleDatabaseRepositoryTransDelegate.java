/*******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2012 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.repository.kdr.delegates;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import org.pentaho.di.cachefile.CacheFile;
import org.pentaho.di.cluster.ClusterSchema;
import org.pentaho.di.cluster.SlaveServer;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.Counter;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.ProgressMonitorListener;
import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleDatabaseException;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.logging.LogTableInterface;
import org.pentaho.di.core.logging.TransLogTable;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.partition.PartitionSchema;
import org.pentaho.di.repository.LongObjectId;
import org.pentaho.di.repository.ObjectId;
import org.pentaho.di.repository.RepositoryAttributeInterface;
import org.pentaho.di.repository.RepositoryDirectory;
import org.pentaho.di.repository.RepositoryDirectoryInterface;
import org.pentaho.di.repository.RepositoryObjectType;
import org.pentaho.di.repository.kdr.KettleDatabaseRepository;
import org.pentaho.di.shared.SharedObjects;
import org.pentaho.di.trans.TransDependency;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.TransMeta.TransformationType;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.trans.step.StepPartitioningMeta;

public class KettleDatabaseRepositoryTransDelegate extends KettleDatabaseRepositoryBaseDelegate {
	
	private static Class<?> PKG = TransMeta.class; // for i18n purposes, needed by Translator2!!   $NON-NLS-1$

	public KettleDatabaseRepositoryTransDelegate(KettleDatabaseRepository repository) {
		super(repository);
	}
	
	public RowMetaAndData getTransformation(ObjectId id_transformation) throws KettleException
	{
		return repository.connectionDelegate.getOneRow(quoteTable(KettleDatabaseRepository.TABLE_R_TRANSFORMATION), quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_TRANSFORMATION), id_transformation);
	}
	
	public RowMetaAndData getTransHop(ObjectId id_trans_hop) throws KettleException
	{
		return repository.connectionDelegate.getOneRow(quoteTable(KettleDatabaseRepository.TABLE_R_TRANS_HOP), quote(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_TRANS_HOP), id_trans_hop);
	}

	public RowMetaAndData getTransDependency(ObjectId id_dependency) throws KettleException
	{
		return repository.connectionDelegate.getOneRow(quoteTable(KettleDatabaseRepository.TABLE_R_DEPENDENCY), quote(KettleDatabaseRepository.FIELD_DEPENDENCY_ID_DEPENDENCY), id_dependency);
	}

	public boolean existsTransMeta(String name, RepositoryDirectoryInterface repositoryDirectory, RepositoryObjectType objectType) throws KettleException {
		try {
			return (getTransformationID(name, repositoryDirectory.getObjectId()) != null);
		} catch (KettleException e) {
			throw new KettleException("Unable to verify if the transformation with name ["+name+"] in directory ["+repositoryDirectory+"] exists", e);
		}
	}

	public synchronized ObjectId getTransformationID(String name, ObjectId id_directory) throws KettleException
	{
		return repository.connectionDelegate.getIDWithValue(quoteTable(KettleDatabaseRepository.TABLE_R_TRANSFORMATION), quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_TRANSFORMATION), quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_NAME), name, quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY), id_directory);
	}

	public synchronized ObjectId getTransHopID(ObjectId id_transformation, ObjectId id_step_from, ObjectId id_step_to) throws KettleException
	{
		String lookupkey[] = new String[] { quote(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_TRANSFORMATION), quote(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_STEP_FROM), quote(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_STEP_TO), };
		ObjectId key[] = new ObjectId[] { id_transformation, id_step_from, id_step_to };

		return repository.connectionDelegate.getIDWithValue(quoteTable(KettleDatabaseRepository.TABLE_R_TRANS_HOP), quote(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_TRANS_HOP), lookupkey, key);
	}

	public synchronized ObjectId getDependencyID(ObjectId id_transformation, ObjectId id_database, String tablename) throws KettleException
	{
		String lookupkey[] = new String[] { quote(KettleDatabaseRepository.FIELD_DEPENDENCY_ID_TRANSFORMATION), quote(KettleDatabaseRepository.FIELD_DEPENDENCY_ID_DATABASE), };
		ObjectId key[] = new ObjectId[] { id_transformation, id_database };

		return repository.connectionDelegate.getIDWithValue(quoteTable(KettleDatabaseRepository.TABLE_R_DEPENDENCY), quote(KettleDatabaseRepository.FIELD_DEPENDENCY_ID_DEPENDENCY), quote(KettleDatabaseRepository.FIELD_DEPENDENCY_TABLE_NAME), tablename, lookupkey, key);
	}




    /**  
     * Saves the transformation to a repository.
     *
     * @param transMeta the transformation metadata to store
     * @param monitor the way we report progress to the user, can be null if no UI is present
     * @param overwrite Overwrite existing object(s)?
     * @throws KettleException if an error occurs.
     */
    public void saveTransformation(TransMeta transMeta, String versionComment, ProgressMonitorListener monitor, boolean overwriteAssociated) throws KettleException
    {
        try
        {
        	if (monitor!=null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.LockingRepository")); //$NON-NLS-1$

        	repository.lockRepository(); // make sure we're they only one using the repository at the moment

        	repository.insertLogEntry("save transformation '"+transMeta.getName()+"'");
            
            // Clear attribute id cache
            repository.connectionDelegate.clearNextIDCounters(); // force repository lookup.

            // Do we have a valid directory?
            if (transMeta.getRepositoryDirectory().getObjectId() == null) { 
            	throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Exception.PlsSelectAValidDirectoryBeforeSavingTheTransformation")); //$NON-NLS-1$
            }

            int nrWorks = 2 + transMeta.nrDatabases() + transMeta.nrNotes() + transMeta.nrSteps() + transMeta.nrTransHops();
            if (monitor != null) monitor.beginTask(BaseMessages.getString(PKG, "TransMeta.Monitor.SavingTransformationTask.Title") + transMeta.getPathAndName(), nrWorks); //$NON-NLS-1$
            if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.SavingOfTransformationStarted")); //$NON-NLS-1$

            if (monitor!=null && monitor.isCanceled()) throw new KettleDatabaseException();

            // Before we start, make sure we have a valid transformation ID!
            // Two possibilities:
            // 1) We have a ID: keep it
            // 2) We don't have an ID: look it up.
            // If we find a transformation with the same name: ask!
            //
            if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.HandlingOldVersionTransformationTask.Title")); //$NON-NLS-1$
            //transMeta.setObjectId(getTransformationID(transMeta.getName(), transMeta.getRepositoryDirectory().getObjectId()));

            // If no valid id is available in the database, assign one...
            if (transMeta.getObjectId() == null)
            {
            	transMeta.setObjectId(repository.connectionDelegate.getNextTransformationID());
            }
            else
            {
                // If we have a valid ID, we need to make sure everything is cleared out
                // of the database for this id_transformation, before we put it back in...
                if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.DeletingOldVersionTransformationTask.Title")); //$NON-NLS-1$
                if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.DeletingOldVersionTransformation")); //$NON-NLS-1$
                repository.deleteTransformation(transMeta.getObjectId());
                if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.OldVersionOfTransformationRemoved")); //$NON-NLS-1$
            }
            if (monitor != null) monitor.worked(1);

            if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.SavingNotes")); //$NON-NLS-1$
            for (int i = 0; i < transMeta.nrNotes(); i++)
            {
                if (monitor!=null && monitor.isCanceled()) throw new KettleDatabaseException(BaseMessages.getString(PKG, "TransMeta.Log.UserCancelledTransSave"));

                // if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.SavingNoteTask.Title") + (i + 1) + "/" + transMeta.nrNotes()); //$NON-NLS-1$ //$NON-NLS-2$
                NotePadMeta ni = transMeta.getNote(i);
                repository.saveNotePadMeta(ni, transMeta.getObjectId());
                if (ni.getObjectId() != null) {
                	repository.insertTransNote(transMeta.getObjectId(), ni.getObjectId());
                }
                if (monitor != null) monitor.worked(1);
            }

            if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.SavingDatabaseConnections")); //$NON-NLS-1$
            for (int i = 0; i < transMeta.nrDatabases(); i++)
            {
                if (monitor!=null && monitor.isCanceled()) throw new KettleDatabaseException(BaseMessages.getString(PKG, "TransMeta.Log.UserCancelledTransSave"));

                // if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.SavingDatabaseTask.Title") + (i + 1) + "/" + transMeta.nrDatabases()); //$NON-NLS-1$ //$NON-NLS-2$
                DatabaseMeta databaseMeta = transMeta.getDatabase(i);
                // Save the database connection if we're overwriting objects or (it has changed and nothing was saved in the repository)
                if(overwriteAssociated || databaseMeta.hasChanged() || databaseMeta.getObjectId()==null)
                {
                	repository.save(databaseMeta, versionComment, monitor, overwriteAssociated);
                }
                if (monitor != null) monitor.worked(1);
            }

            // Before saving the steps, make sure we have all the step-types.
            // It is possible that we received another step through a plugin.
            if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.CheckingStepTypes")); //$NON-NLS-1$
            repository.updateStepTypes();
            repository.updateDatabaseTypes();

            if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.SavingSteps")); //$NON-NLS-1$
            for (int i = 0; i < transMeta.nrSteps(); i++)
            {
                if (monitor!=null && monitor.isCanceled()) throw new KettleDatabaseException(BaseMessages.getString(PKG, "TransMeta.Log.UserCancelledTransSave"));

                // if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.SavingStepTask.Title") + (i + 1) + "/" + transMeta.nrSteps()); //$NON-NLS-1$ //$NON-NLS-2$
                StepMeta stepMeta = transMeta.getStep(i);
                repository.stepDelegate.saveStepMeta(stepMeta, transMeta.getObjectId());

                if (monitor != null) monitor.worked(1);
            }
            repository.connectionDelegate.closeStepAttributeInsertPreparedStatement();

            if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.SavingHops")); //$NON-NLS-1$
            for (int i = 0; i < transMeta.nrTransHops(); i++)
            {
                if (monitor!=null && monitor.isCanceled()) throw new KettleDatabaseException(BaseMessages.getString(PKG, "TransMeta.Log.UserCancelledTransSave"));

                // if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.SavingHopTask.Title") + (i + 1) + "/" + transMeta.nrTransHops()); //$NON-NLS-1$ //$NON-NLS-2$
                TransHopMeta hi = transMeta.getTransHop(i);
                saveTransHopMeta(hi, transMeta.getObjectId());
                if (monitor != null) monitor.worked(1);
            }

            // if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.FinishingTask.Title")); //$NON-NLS-1$
            if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.SavingTransformationInfo")); //$NON-NLS-1$
            
            insertTransformation(transMeta); // save the top level information for the transformation
            
            saveTransParameters(transMeta);
            
            repository.connectionDelegate.closeTransAttributeInsertPreparedStatement();
            
            // Save the partition schemas
            //
            for (int i=0;i<transMeta.getPartitionSchemas().size();i++)
            {
                if (monitor!=null && monitor.isCanceled()) throw new KettleDatabaseException(BaseMessages.getString(PKG, "TransMeta.Log.UserCancelledTransSave"));

                PartitionSchema partitionSchema = transMeta.getPartitionSchemas().get(i);
                // See if this transformation really is a consumer of this object
                // It might be simply loaded as a shared object from the repository
                //
                boolean isUsedByTransformation = transMeta.isUsingPartitionSchema(partitionSchema);
                repository.save(partitionSchema, versionComment, null, transMeta.getObjectId(), isUsedByTransformation, overwriteAssociated);
            }
            
            // Save the slaves
            //
            for (int i=0;i<transMeta.getSlaveServers().size();i++)
            {
                if (monitor!=null && monitor.isCanceled()) throw new KettleDatabaseException(BaseMessages.getString(PKG, "TransMeta.Log.UserCancelledTransSave"));

                SlaveServer slaveServer = transMeta.getSlaveServers().get(i);
                boolean isUsedByTransformation = transMeta.isUsingSlaveServer(slaveServer);
                repository.save(slaveServer, versionComment, null, transMeta.getObjectId(), isUsedByTransformation, overwriteAssociated);
            }
            
            // Save the clustering schemas
            for (int i=0;i<transMeta.getClusterSchemas().size();i++)
            {
                if (monitor!=null && monitor.isCanceled()) throw new KettleDatabaseException(BaseMessages.getString(PKG, "TransMeta.Log.UserCancelledTransSave"));

                ClusterSchema clusterSchema = transMeta.getClusterSchemas().get(i);
                boolean isUsedByTransformation = transMeta.isUsingClusterSchema(clusterSchema);
                repository.save(clusterSchema, versionComment, null, transMeta.getObjectId(), isUsedByTransformation, overwriteAssociated);
            }
            
            if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.SavingDependencies")); //$NON-NLS-1$
            for (int i = 0; i < transMeta.nrDependencies(); i++)
            {
                if (monitor!=null && monitor.isCanceled()) throw new KettleDatabaseException(BaseMessages.getString(PKG, "TransMeta.Log.UserCancelledTransSave"));

                TransDependency td = transMeta.getDependency(i);
                saveTransDependency(td, transMeta.getObjectId());
            }           
            
            // Save the step error handling information as well!
            for (int i=0;i<transMeta.nrSteps();i++)
            {
                StepMeta stepMeta = transMeta.getStep(i);
                StepErrorMeta stepErrorMeta = stepMeta.getStepErrorMeta();
                if (stepErrorMeta!=null)
                {
                    repository.stepDelegate.saveStepErrorMeta(stepErrorMeta, transMeta.getObjectId(), stepMeta.getObjectId());
                }
            }
            repository.connectionDelegate.closeStepAttributeInsertPreparedStatement();
            
            if(log.isDebug()) log.logDebug(BaseMessages.getString(PKG, "TransMeta.Log.SavingFinished")); //$NON-NLS-1$

        	if (monitor!=null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.UnlockingRepository")); //$NON-NLS-1$
        	repository.unlockRepository();

            // Perform a commit!
        	repository.commit();

            transMeta.clearChanged();
            if (monitor != null) monitor.worked(1);
            if (monitor != null) monitor.done();
        }
        catch (KettleDatabaseException dbe)
        {
        	dbe.printStackTrace();
            // Oops, roll back!
        	repository.rollback();

            log.logError(BaseMessages.getString(PKG, "TransMeta.Log.ErrorSavingTransformationToRepository") + Const.CR + dbe.getMessage()); //$NON-NLS-1$
            throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Log.ErrorSavingTransformationToRepository"), dbe); //$NON-NLS-1$
        }
        finally
        {
            // don't forget to unlock the repository.
            // Normally this is done by the commit / rollback statement, but hey there are some freaky database out
            // there...
        	repository.unlockRepository();
        }
    }
    
    
    /**
     * Save the parameters of this transformation to the repository.
     * 
     * @param rep The repository to save to.
     * 
     * @throws KettleException Upon any error.
     * 
     * 
     * TODO: Move this code over to the Repository class for refactoring...
     */
    public void saveTransParameters(TransMeta transMeta) throws KettleException
    {
    	String[] paramKeys = transMeta.listParameters();
    	for (int idx = 0; idx < paramKeys.length; idx++)  {
    		String desc = transMeta.getParameterDescription(paramKeys[idx]);
    		String defaultValue = transMeta.getParameterDefault(paramKeys[idx]);
    		insertTransParameter(transMeta.getObjectId(), idx, paramKeys[idx], defaultValue, desc);
    	}
    }

    
    
    /** Read a transformation with a certain name from a repository
    *
    * @param rep The repository to read from.
    * @param transname The name of the transformation.
    * @param repdir the path to the repository directory
    * @param monitor The progress monitor to display the progress of the file-open operation in a dialog
    * @param setInternalVariables true if you want to set the internal variables based on this transformation information
    */
   public TransMeta loadTransformation(TransMeta transMeta, String transname, RepositoryDirectoryInterface repdir, ProgressMonitorListener monitor, boolean setInternalVariables) throws KettleException
   {
       transMeta.setRepository(repository);
       
       synchronized(repository) 
       {
	        try
	        {
	            String pathAndName = repdir.isRoot() ? repdir + transname : repdir + RepositoryDirectory.DIRECTORY_SEPARATOR + transname;
	
	            transMeta.setName(transname);
	            transMeta.setRepositoryDirectory(repdir);
	            
	            // Get the transformation id
	            if(log.isDetailed()) log.logDetailed(BaseMessages.getString(PKG, "TransMeta.Log.LookingForTransformation", transname, repdir.getPath() )); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	
	            if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.ReadingTransformationInfoTask.Title")); //$NON-NLS-1$
	            transMeta.setObjectId(getTransformationID(transname, repdir.getObjectId()));
	            if (monitor != null) monitor.worked(1);
	
	            // If no valid id is available in the database, then give error...
	            if (transMeta.getObjectId() != null)
	            {
	                ObjectId noteids[] = repository.getTransNoteIDs(transMeta.getObjectId());
	                ObjectId stepids[] = repository.getStepIDs(transMeta.getObjectId());
	                ObjectId hopids[] = getTransHopIDs(transMeta.getObjectId());
	
	                int nrWork = 3 + noteids.length + stepids.length + hopids.length;
	
	                if (monitor != null) monitor.beginTask(BaseMessages.getString(PKG, "TransMeta.Monitor.LoadingTransformationTask.Title") + pathAndName, nrWork); //$NON-NLS-1$
	
	                if(log.isDetailed()) log.logDetailed(BaseMessages.getString(PKG, "TransMeta.Log.LoadingTransformation", transMeta.getName() )); //$NON-NLS-1$ //$NON-NLS-2$
	
	                // Load the common database connections
	                if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.ReadingTheAvailableSharedObjectsTask.Title")); //$NON-NLS-1$
	                try
	                {
	                	transMeta.setSharedObjects(readTransSharedObjects(transMeta));
	                }
	                catch(Exception e)
	                {
	                    log.logError(BaseMessages.getString(PKG, "TransMeta.ErrorReadingSharedObjects.Message", e.toString()));
	                    log.logError(Const.getStackTracker(e));
	                }
	
	                if (monitor != null) monitor.worked(1);
	
	                // Load the notes...
	                if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.ReadingNoteTask.Title")); //$NON-NLS-1$
	                for (int i = 0; i < noteids.length; i++)
	                {
	                    NotePadMeta ni = repository.notePadDelegate.loadNotePadMeta(noteids[i]);
	                    if (transMeta.indexOfNote(ni) < 0) {
	                    	transMeta.addNote(ni);
	                    }
	                    if (monitor != null) monitor.worked(1);
	                }
	
	                if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.ReadingStepsTask.Title")); //$NON-NLS-1$
	                repository.connectionDelegate.fillStepAttributesBuffer(transMeta.getObjectId()); // read all the attributes on one go!
	                for (int i = 0; i < stepids.length; i++)
	                {
	                	if(log.isDetailed()) log.logDetailed(BaseMessages.getString(PKG, "TransMeta.Log.LoadingStepWithID") + stepids[i]); //$NON-NLS-1$
	                    if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.ReadingStepTask.Title") + (i + 1) + "/" + (stepids.length)); //$NON-NLS-1$ //$NON-NLS-2$
	                    StepMeta stepMeta = repository.stepDelegate.loadStepMeta(stepids[i], transMeta.getDatabases(), transMeta.getCounters(), transMeta.getPartitionSchemas());
	                    // In this case, we just add or replace the shared steps.
	                    // The repository is considered "more central"
	                    transMeta.addOrReplaceStep(stepMeta);
	                    
	                    if (monitor != null) monitor.worked(1);
	                }
	                if (monitor != null) monitor.worked(1);
	                repository.connectionDelegate.setStepAttributesBuffer(null); // clear the buffer (should be empty anyway)
	
	                // Have all StreamValueLookups, etc. reference the correct source steps...
	                for (int i = 0; i < transMeta.nrSteps(); i++)
	                {
	                    StepMetaInterface sii = transMeta.getStep(i).getStepMetaInterface();
	                    sii.searchInfoAndTargetSteps(transMeta.getSteps());
	                }
	
	                if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.LoadingTransformationDetailsTask.Title")); //$NON-NLS-1$
	                loadRepTrans(transMeta);
	                if (monitor != null) monitor.worked(1);
	                                                
	                if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.ReadingHopTask.Title")); //$NON-NLS-1$
	                for (int i = 0; i < hopids.length; i++)
	                {
	                    TransHopMeta hi = loadTransHopMeta(hopids[i], transMeta.getSteps());
	                    if (hi!=null) {
  	                    transMeta.addTransHop(hi);
	                    }
	                    if (monitor != null) monitor.worked(1);
	                }
	                
	                // Have all step partitioning meta-data reference the correct schemas that we just loaded
	                // 
	                for (int i = 0; i < transMeta.nrSteps(); i++)
	                {
	                    StepPartitioningMeta stepPartitioningMeta = transMeta.getStep(i).getStepPartitioningMeta();
	                    if (stepPartitioningMeta!=null)
	                    {
	                        stepPartitioningMeta.setPartitionSchemaAfterLoading(transMeta.getPartitionSchemas());
	                    }
	                }
	                
	                // Have all step clustering schema meta-data reference the correct cluster schemas that we just loaded
	                // 
	                for (int i = 0; i < transMeta.nrSteps(); i++)
	                {
	                	transMeta.getStep(i).setClusterSchemaAfterLoading(transMeta.getClusterSchemas());
	                }                
	                
	                if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.ReadingTheDependenciesTask.Title")); //$NON-NLS-1$
	                ObjectId depids[] = repository.getTransDependencyIDs(transMeta.getObjectId());
	                for (int i = 0; i < depids.length; i++)
	                {
	                    TransDependency td = loadTransDependency(depids[i], transMeta.getDatabases());
	                    transMeta.addDependency(td);
	                }
	                if (monitor != null) monitor.worked(1);
	
	                // Also load the step error handling metadata
	                //
	                for (int i=0;i<transMeta.nrSteps();i++)
	                {
	                    StepMeta stepMeta = transMeta.getStep(i);
	                    String sourceStep = repository.getStepAttributeString(stepMeta.getObjectId(), "step_error_handling_source_step");
	                    if (sourceStep!=null)
	                    {
	                        StepErrorMeta stepErrorMeta = repository.stepDelegate.loadStepErrorMeta(transMeta, stepMeta, transMeta.getSteps());
	                        stepErrorMeta.getSourceStep().setStepErrorMeta(stepErrorMeta); // a bit of a trick, I know.                        
	                    }
	                }
	                
	                // Load all the log tables for the transformation...
	                //
	                RepositoryAttributeInterface attributeInterface = new KettleDatabaseRepositoryTransAttribute(repository.connectionDelegate, transMeta.getObjectId());
	                for (LogTableInterface logTable : transMeta.getLogTables()) {
	                  //xnren start 2015-05-26
	                  //improve:when you did not set logTable, do not load logtable from repository
	                  if(logTable.getConnectionName() != null && logTable.getTableName() != null){
	                	  logTable.loadFromRepository(attributeInterface);  
	                  }
	                  //xnren end
	                }	                
	                
	                if (monitor != null) monitor.subTask(BaseMessages.getString(PKG, "TransMeta.Monitor.SortingStepsTask.Title")); //$NON-NLS-1$
	                transMeta.sortSteps();
	                if (monitor != null) monitor.worked(1);
	                if (monitor != null) monitor.done();
	            }
	            else
	            {
	                throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Exception.TransformationDoesNotExist") + transMeta.getName()); //$NON-NLS-1$
	            }
	            if(log.isDetailed()) 
	            {
	            	log.logDetailed(BaseMessages.getString(PKG, "TransMeta.Log.LoadedTransformation2", transname , String.valueOf(transMeta.getRepositoryDirectory() == null))); //$NON-NLS-1$ //$NON-NLS-2$
	            	log.logDetailed(BaseMessages.getString(PKG, "TransMeta.Log.LoadedTransformation", transname , transMeta.getRepositoryDirectory().getPath() )); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	            }
	            
	            return transMeta;
	        }
	        catch (KettleDatabaseException e)
	        {
	            log.logError(BaseMessages.getString(PKG, "TransMeta.Log.DatabaseErrorOccuredReadingTransformation") + Const.CR + e); //$NON-NLS-1$
	            throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Exception.DatabaseErrorOccuredReadingTransformation"), e); //$NON-NLS-1$
	        }
	        catch (Exception e)
	        {
	            log.logError(BaseMessages.getString(PKG, "TransMeta.Log.DatabaseErrorOccuredReadingTransformation") + Const.CR + e); //$NON-NLS-1$
	            throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Exception.DatabaseErrorOccuredReadingTransformation2"), e); //$NON-NLS-1$
	        }
	        finally
	        {
	        	transMeta.initializeVariablesFrom(null);
	        	if (setInternalVariables) {
	        		transMeta.setInternalKettleVariables();
	        	}
	        }
       }
   }
   

   /**      
    * Load the transformation name & other details from a repository.
    *
    * @param rep The repository to load the details from.
    */
   private void loadRepTrans(TransMeta transMeta) throws KettleException
   {
       try
       {
           RowMetaAndData r = getTransformation(transMeta.getObjectId());

           if (r != null)
           {
               transMeta.setName( r.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_NAME, null) ); //$NON-NLS-1$

				// Trans description
                transMeta.setDescription( r.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_DESCRIPTION, null) );
                transMeta.setExtendedDescription( r.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_EXTENDED_DESCRIPTION, null) );
                transMeta.setTransversion( r.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_TRANS_VERSION, null) );
				transMeta.setTransstatus( (int) r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_TRANS_STATUS, -1L) );

				TransLogTable logTable = transMeta.getTransLogTable();
				logTable.findField(TransLogTable.ID.LINES_READ).setSubject( StepMeta.findStep(transMeta.getSteps(), new LongObjectId(r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_READ, -1L))) );
				
				logTable.findField(TransLogTable.ID.LINES_READ).setSubject( StepMeta.findStep(transMeta.getSteps(), new LongObjectId(r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_WRITE, -1L))) ); 
				logTable.findField(TransLogTable.ID.LINES_READ).setSubject( StepMeta.findStep(transMeta.getSteps(), new LongObjectId(r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_INPUT, -1L))) );
				logTable.findField(TransLogTable.ID.LINES_READ).setSubject( StepMeta.findStep(transMeta.getSteps(), new LongObjectId(r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_OUTPUT, -1L))) );
				logTable.findField(TransLogTable.ID.LINES_READ).setSubject( StepMeta.findStep(transMeta.getSteps(), new LongObjectId(r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_UPDATE, -1L))) );
               
                long id_rejected = getTransAttributeInteger(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_ID_STEP_REJECTED); // $NON-NLS-1$
                if (id_rejected>0)
                {
                	logTable.findField(TransLogTable.ID.LINES_REJECTED).setSubject( StepMeta.findStep(transMeta.getSteps(), new LongObjectId(id_rejected)) ); //$NON-NLS-1$
                }
                
                DatabaseMeta logDb = DatabaseMeta.findDatabase(transMeta.getDatabases(), new LongObjectId(r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DATABASE_LOG, -1L)));
                if(logDb != null) {
                  logTable.setConnectionName( logDb.getName() ); //$NON-NLS-1$
                  // TODO: save/load name as a string, allow variables! 
                }
                
                logTable.setTableName( r.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_TABLE_NAME_LOG, null) ); //$NON-NLS-1$
                logTable.setBatchIdUsed( r.getBoolean(KettleDatabaseRepository.FIELD_TRANSFORMATION_USE_BATCHID, false) ); //$NON-NLS-1$
                logTable.setLogFieldUsed( r.getBoolean(KettleDatabaseRepository.FIELD_TRANSFORMATION_USE_LOGFIELD, false) ); //$NON-NLS-1$
 
                transMeta.setMaxDateConnection(  DatabaseMeta.findDatabase(transMeta.getDatabases(), new LongObjectId(r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DATABASE_MAXDATE, -1L))) ); //$NON-NLS-1$
                transMeta.setMaxDateTable( r.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_TABLE_NAME_MAXDATE, null) ); //$NON-NLS-1$
                transMeta.setMaxDateField( r.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_FIELD_NAME_MAXDATE, null) ); //$NON-NLS-1$
                transMeta.setMaxDateOffset(r.getNumber(KettleDatabaseRepository.FIELD_TRANSFORMATION_OFFSET_MAXDATE, 0.0) ); //$NON-NLS-1$
                transMeta.setMaxDateDifference( r.getNumber(KettleDatabaseRepository.FIELD_TRANSFORMATION_DIFF_MAXDATE, 0.0) ); //$NON-NLS-1$

                transMeta.setCreatedUser(r.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_CREATED_USER, null) ); //$NON-NLS-1$
                transMeta.setCreatedDate( r.getDate(KettleDatabaseRepository.FIELD_TRANSFORMATION_CREATED_DATE, null) ); //$NON-NLS-1$

                transMeta.setModifiedUser( r.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_MODIFIED_USER, null) ); //$NON-NLS-1$
                transMeta.setModifiedDate( r.getDate(KettleDatabaseRepository.FIELD_TRANSFORMATION_MODIFIED_DATE, null) ); //$NON-NLS-1$

                // Optional:
                transMeta.setSizeRowset( Const.ROWS_IN_ROWSET );
                Long val_size_rowset = r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_SIZE_ROWSET); //$NON-NLS-1$
                if (val_size_rowset != null )
                {
                	transMeta.setSizeRowset( val_size_rowset.intValue() );
                }

                long id_directory = r.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY, -1L); //$NON-NLS-1$
                if (id_directory >= 0)
                {
               	   if(log.isDetailed()) log.logDetailed("ID_DIRECTORY=" + id_directory); //$NON-NLS-1$
                   // Set right directory...
               	   transMeta.setRepositoryDirectory( repository.loadRepositoryDirectoryTree().findDirectory(new LongObjectId(id_directory)) ); // always reload the folder structure
                }
               
                transMeta.setUsingUniqueConnections( getTransAttributeBoolean(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_UNIQUE_CONNECTIONS) );
                transMeta.setFeedbackShown( !"N".equalsIgnoreCase( getTransAttributeString(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_FEEDBACK_SHOWN) ) );
                transMeta.setFeedbackSize( (int) getTransAttributeInteger(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_FEEDBACK_SIZE) );
                transMeta.setUsingThreadPriorityManagment( !"N".equalsIgnoreCase( getTransAttributeString(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_USING_THREAD_PRIORITIES) ) );    
               
                // Performance monitoring for steps...
                //
                transMeta.setCapturingStepPerformanceSnapShots( getTransAttributeBoolean(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_CAPTURE_STEP_PERFORMANCE) );
                transMeta.setStepPerformanceCapturingDelay( getTransAttributeInteger(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_STEP_PERFORMANCE_CAPTURING_DELAY) );
                transMeta.setStepPerformanceCapturingSizeLimit(getTransAttributeString(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_STEP_PERFORMANCE_CAPTURING_SIZE_LIMIT) );
                transMeta.getPerformanceLogTable().setTableName( getTransAttributeString(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_STEP_PERFORMANCE_LOG_TABLE) );
                transMeta.getTransLogTable().setLogSizeLimit( getTransAttributeString(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_LOG_SIZE_LIMIT) );
                transMeta.getTransLogTable().setLogInterval( getTransAttributeString(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_LOG_INTERVAL) );
                transMeta.setTransformationType( TransformationType.getTransformationTypeByCode( getTransAttributeString(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_TRANSFORMATION_TYPE)) );

                loadRepParameters(transMeta);
           }
       }
       catch (KettleDatabaseException dbe)
       {
           throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Exception.UnableToLoadTransformationInfoFromRepository"), dbe); //$NON-NLS-1$
       }
       finally
       {
       	   transMeta.initializeVariablesFrom(null);
           transMeta.setInternalKettleVariables();
       }
   }


   /**
    * Load the parameters of this transformation from the repository. The current 
    * ones already loaded will be erased.
    * 
    * @param rep The repository to load from.
    * 
    * @throws KettleException Upon any error.
    */
   private void loadRepParameters(TransMeta transMeta) throws KettleException
   {
   	transMeta.eraseParameters();

   	int count = countTransParameter(transMeta.getObjectId());
   	for (int idx = 0; idx < count; idx++)  {
   		String key  = getTransParameterKey(transMeta.getObjectId(), idx);
   		String def  = getTransParameterDefault(transMeta.getObjectId(), idx);
   		String desc = getTransParameterDescription(transMeta.getObjectId(), idx);
   		transMeta.addParameterDefinition(key, def, desc);
   	}
   }    
   
	/**
	 * Count the number of parameters of a transaction.
	 * 
	 * @param id_transformation transformation id
	 * @return the number of transactions
	 * 
	 * @throws KettleException Upon any error.
	 */
	public int countTransParameter(ObjectId id_transformation) throws KettleException  {
		return repository.connectionDelegate.countNrTransAttributes(id_transformation, KettleDatabaseRepository.TRANS_ATTRIBUTE_PARAM_KEY);
	}
	
	/**
	 * Get a transformation parameter key. You can count the number of parameters up front.
	 * 
	 * @param id_transformation transformation id
	 * @param nr number of the parameter 
	 * @return they key/name of specified parameter
	 * 
	 * @throws KettleException Upon any error.
	 */
	public String getTransParameterKey(ObjectId id_transformation, int nr) throws KettleException  {
		return repository.connectionDelegate.getTransAttributeString(id_transformation, nr, KettleDatabaseRepository.TRANS_ATTRIBUTE_PARAM_KEY);		
	}

	/**
	 * Get a transformation parameter default. You can count the number of parameters up front. 
	 * 
	 * @param id_transformation transformation id
	 * @param nr number of the parameter
	 * @return
	 * 
	 * @throws KettleException Upon any error.
	 */
	public String getTransParameterDefault(ObjectId id_transformation, int nr) throws KettleException  {
		return repository.connectionDelegate.getTransAttributeString(id_transformation, nr, KettleDatabaseRepository.TRANS_ATTRIBUTE_PARAM_DEFAULT);		
	}	
	
	/**
	 * Get a transformation parameter description. You can count the number of parameters up front. 
	 * 
	 * @param id_transformation transformation id
	 * @param nr number of the parameter
	 * @return
	 * 
	 * @throws KettleException Upon any error.
	 */
	public String getTransParameterDescription(ObjectId id_transformation, int nr) throws KettleException  {
		return repository.connectionDelegate.getTransAttributeString(id_transformation, nr, KettleDatabaseRepository.TRANS_ATTRIBUTE_PARAM_DESCRIPTION);		
	}
	
	/**
	 * Insert a parameter for a transformation in the repository.
	 * 
	 * @param id_transformation transformation id
	 * @param nr number of the parameter to insert
	 * @param key key to insert
	 * @param defValue default value
	 * @param description description to insert
	 * 
	 * @throws KettleException Upon any error.
	 */
	public void insertTransParameter(ObjectId id_transformation, long nr, String key, String defValue, String description) throws KettleException {
	    repository.connectionDelegate.insertTransAttribute(id_transformation, nr, KettleDatabaseRepository.TRANS_ATTRIBUTE_PARAM_KEY, 0, key != null ? key : "");		
	    repository.connectionDelegate.insertTransAttribute(id_transformation, nr, KettleDatabaseRepository.TRANS_ATTRIBUTE_PARAM_DEFAULT, 0, defValue != null ? defValue : "");
	    repository.connectionDelegate.insertTransAttribute(id_transformation, nr, KettleDatabaseRepository.TRANS_ATTRIBUTE_PARAM_DESCRIPTION, 0, description != null ? description : "");
	}

	

   
   
   /**
    * Read all the databases from the repository, insert into the TransMeta object, overwriting optionally
    * 
    * @param TransMeta The transformation to load into.
    * @param overWriteShared if an object with the same name exists, overwrite
    * @throws KettleException 
    */
   public void readDatabases(TransMeta transMeta, boolean overWriteShared) throws KettleException
   {
       try
       {
           ObjectId dbids[] = repository.getDatabaseIDs(false);
           for (int i = 0; i < dbids.length; i++)
           {
               DatabaseMeta databaseMeta = repository.loadDatabaseMeta(dbids[i], null); // reads last version
               databaseMeta.shareVariablesWith(transMeta);
               
               DatabaseMeta check = transMeta.findDatabase(databaseMeta.getName()); // Check if there already is one in the transformation
               if (check==null || overWriteShared) // We only add, never overwrite database connections. 
               {
                   if (databaseMeta.getName() != null)
                   {
                	   transMeta.addOrReplaceDatabase(databaseMeta);
                       if (!overWriteShared) databaseMeta.setChanged(false);
                   }
               }
           }
           transMeta.clearChangedDatabases();
       }
       catch (KettleDatabaseException dbe)
       {
           throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Log.UnableToReadDatabaseIDSFromRepository"), dbe); //$NON-NLS-1$
       }
       catch (KettleException ke)
       {
           throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Log.UnableToReadDatabasesFromRepository"), ke); //$NON-NLS-1$
       }
   }


   /**
    * Read the clusters in the repository and add them to this transformation if they are not yet present.
    * @param TransMeta The transformation to load into.
    * @param overWriteShared if an object with the same name exists, overwrite
    * @throws KettleException 
    */
   public void readClusters(TransMeta transMeta, boolean overWriteShared) throws KettleException
   {
       try
       {
           ObjectId dbids[] = repository.getClusterIDs(false);
           for (int i = 0; i < dbids.length; i++)
           {
               ClusterSchema clusterSchema = repository.loadClusterSchema(dbids[i], transMeta.getSlaveServers(), null); // Load the last version
               clusterSchema.shareVariablesWith(transMeta);
               ClusterSchema check = transMeta.findClusterSchema(clusterSchema.getName()); // Check if there already is one in the transformation
               if (check==null || overWriteShared) 
               {
                   if (!Const.isEmpty(clusterSchema.getName()))
                   {
                       transMeta.addOrReplaceClusterSchema(clusterSchema);
                       if (!overWriteShared) clusterSchema.setChanged(false);
                   }
               }
           }
       }
       catch (KettleDatabaseException dbe)
       {
           throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Log.UnableToReadClustersFromRepository"), dbe); //$NON-NLS-1$
       }
   }
   
   /**
    * add by cli
    * read the cache files in the repository and add them to this transformation if they are not yet present
    * @param transMeta
    * @param overWriteShared
    * @throws KettleException
    */
   public void readCacheFiles(TransMeta transMeta,boolean overWriteShared)throws KettleException
   {
	   ObjectId cfids[] = repository.getCacheFileIDs(false);
	   for(int i=0;i<cfids.length;i++){
		   CacheFile cf = repository.loadCacheFile(cfids[i], null);
           CacheFile check = transMeta.findCacheFile(cf.getName()); // Check if there already is one in the transformation
           if (check==null || overWriteShared) 
           {
               if (!Const.isEmpty(cf.getName()))
               {
            	   transMeta.addOrReplaceCacheFile(cf);
                   if (!overWriteShared) cf.setChanged(false);
               }
           }		   
	   }
   }
   /**
    * Read the partitions in the repository and add them to this transformation if they are not yet present.
    * @param TransMeta The transformation to load into.
    * @param overWriteShared if an object with the same name exists, overwrite
    * @throws KettleException 
    */
   public void readPartitionSchemas(TransMeta transMeta, boolean overWriteShared) throws KettleException
   {
       try
       {
    	   ObjectId dbids[] = repository.getPartitionSchemaIDs(false);
           for (int i = 0; i < dbids.length; i++)
           {
               PartitionSchema partitionSchema = repository.loadPartitionSchema(dbids[i], null);  // Load last version 
               PartitionSchema check = transMeta.findPartitionSchema(partitionSchema.getName()); // Check if there already is one in the transformation
               if (check==null || overWriteShared) 
               {
                   if (!Const.isEmpty(partitionSchema.getName()))
                   {
                	   transMeta.addOrReplacePartitionSchema(partitionSchema);
                       if (!overWriteShared) partitionSchema.setChanged(false);
                   }
               }
           }
       }
       catch (KettleException dbe)
       {
           throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Log.UnableToReadPartitionSchemaFromRepository"), dbe); //$NON-NLS-1$
       }
   }

   /**
    * Read the slave servers in the repository and add them to this transformation if they are not yet present.
    * @param TransMeta The transformation to load into.
    * @param overWriteShared if an object with the same name exists, overwrite
    * @throws KettleException 
    */
   public void readSlaves(TransMeta transMeta, boolean overWriteShared) throws KettleException
   {
       try
       {
    	   ObjectId dbids[] = repository.getSlaveIDs(false);
           for (int i = 0; i < dbids.length; i++)
           {
               SlaveServer slaveServer = repository.loadSlaveServer(dbids[i], null);  // Load last version
               slaveServer.shareVariablesWith(transMeta);
               SlaveServer check = transMeta.findSlaveServer(slaveServer.getName()); // Check if there already is one in the transformation
               if (check==null || overWriteShared) 
               {
                   if (!Const.isEmpty(slaveServer.getName()))
                   {
                	   transMeta.addOrReplaceSlaveServer(slaveServer);
                       if (!overWriteShared) slaveServer.setChanged(false);
                   }
               }
           }
       }
       catch (KettleDatabaseException dbe)
       {
           throw new KettleException(BaseMessages.getString(PKG, "TransMeta.Log.UnableToReadSlaveServersFromRepository"), dbe); //$NON-NLS-1$
       }
   }
   
   
	public TransDependency loadTransDependency(ObjectId id_dependency, List<DatabaseMeta> databases) throws KettleException
	{
		TransDependency transDependency = new TransDependency();
		
		try
		{
			transDependency.setObjectId(id_dependency);
			
			RowMetaAndData r = getTransDependency(id_dependency);
			
			if (r!=null)
			{
				long id_connection = r.getInteger("ID_DATABASE", 0); //$NON-NLS-1$
				transDependency.setDatabase( DatabaseMeta.findDatabase(databases, new LongObjectId(id_connection)) );
				transDependency.setTablename(  r.getString("TABLE_NAME", null) ); //$NON-NLS-1$
				transDependency.setFieldname( r.getString("FIELD_NAME", null) ); //$NON-NLS-1$
			}
			
			return transDependency;
		}
		catch(KettleException dbe)
		{
			throw new KettleException(BaseMessages.getString(PKG, "TransDependency.Exception.UnableToLoadTransformationDependency")+id_dependency, dbe); //$NON-NLS-1$
		}
	}

	public void saveTransDependency(TransDependency transDependency, ObjectId id_transformation) throws KettleException
	{
		try
		{
			ObjectId id_database = transDependency.getDatabase()==null? null : transDependency.getDatabase().getObjectId();
			
			transDependency.setObjectId( insertDependency(id_transformation, id_database, transDependency.getTablename(), transDependency.getFieldname()) );
		}
		catch(KettleException dbe)
		{
			throw new KettleException(BaseMessages.getString(PKG, "TransDependency.Exception.UnableToSaveTransformationDepency")+id_transformation, dbe); //$NON-NLS-1$
		}
	}

	
	

	
	public void saveTransHopMeta(TransHopMeta transHopMeta, ObjectId id_transformation) throws KettleException
	{
		try
		{
			// See if a transformation hop with the same fromstep and tostep is
			// already available...
			ObjectId id_step_from = transHopMeta.getFromStep() == null ? null : transHopMeta.getFromStep().getObjectId();
			ObjectId id_step_to = transHopMeta.getToStep() == null ? null : transHopMeta.getToStep().getObjectId();

			// Insert new transMeta hop in repository
			transHopMeta.setObjectId(insertTransHop(id_transformation, id_step_from, id_step_to, transHopMeta.isEnabled()));
		} catch (KettleDatabaseException dbe)
		{
			throw new KettleException(
					BaseMessages.getString(PKG, "TransHopMeta.Exception.UnableToSaveTransformationHopInfo") + id_transformation, dbe); //$NON-NLS-1$
		}
	}

  public TransHopMeta loadTransHopMeta(ObjectId id_trans_hop, List<StepMeta> steps) throws KettleException {
    TransHopMeta hopTransMeta = new TransHopMeta();
    try {
      hopTransMeta.setObjectId(id_trans_hop);

      RowMetaAndData r = getTransHop(id_trans_hop);

      hopTransMeta.setEnabled(r.getBoolean("ENABLED", false)); //$NON-NLS-1$

      long id_step_from = r.getInteger("ID_STEP_FROM", 0); //$NON-NLS-1$
      long id_step_to = r.getInteger("ID_STEP_TO", 0); //$NON-NLS-1$

      StepMeta fromStep = StepMeta.findStep(steps, new LongObjectId(id_step_from));

      // Links to a shared objects, try again by looking up the name...
      //
      if (fromStep == null && id_step_from > 0) {
        // Simply load this, we only want the name, we don't care about the
        // rest...
        //
        StepMeta stepMeta = repository.stepDelegate.loadStepMeta(new LongObjectId(id_step_from), new ArrayList<DatabaseMeta>(), new Hashtable<String, Counter>(), new ArrayList<PartitionSchema>());
        fromStep = StepMeta.findStep(steps, stepMeta.getName());
      }

      if (fromStep == null) {
        log.logError("Unable to determine source step of transformation hop with ID: "+id_trans_hop);
        return null; // Invalid hop, simply ignore. See: PDI-2446
      }
      hopTransMeta.setFromStep(fromStep);

      hopTransMeta.getFromStep().setDraw(true);

      hopTransMeta.setToStep(StepMeta.findStep(steps, new LongObjectId(id_step_to)));

      // Links to a shared objects, try again by looking up the name...
      //
      if (hopTransMeta.getToStep() == null && id_step_to > 0) {
        // Simply load this, we only want the name, we don't care about
        // the rest...
        StepMeta stepMeta = repository.stepDelegate.loadStepMeta(new LongObjectId(id_step_to), new ArrayList<DatabaseMeta>(), new Hashtable<String, Counter>(), new ArrayList<PartitionSchema>());
        hopTransMeta.setToStep(StepMeta.findStep(steps, stepMeta.getName()));
      }
      
      if (hopTransMeta.getFromStep()==null || hopTransMeta.getFromStep()==null) {
        // This not a valid hop.  Skipping it is better than refusing to load the transformation.  PDI-5519 
        //
        return null;
      }
      hopTransMeta.getToStep().setDraw(true);

      return hopTransMeta;
    } catch (KettleDatabaseException dbe) {
      throw new KettleException(BaseMessages.getString(PKG, "TransHopMeta.Exception.LoadTransformationHopInfo") + id_trans_hop, dbe); //$NON-NLS-1$
    }
  }


	public synchronized int getNrTransformations(ObjectId id_directory) throws KettleException
	{
		int retval = 0;

    RowMetaAndData par = repository.connectionDelegate.getParameterMetaData(id_directory);
		String sql = "SELECT COUNT(*) FROM "+quoteTable(KettleDatabaseRepository.TABLE_R_TRANSFORMATION)+" WHERE "+quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY)+" = ? ";
		RowMetaAndData r = repository.connectionDelegate.getOneRow(sql, par.getRowMeta(), par.getData());
		if (r != null)
		{
			retval = (int) r.getInteger(0, 0L);
		}

		return retval;
	}


	public synchronized int getNrTransHops(ObjectId id_transformation) throws KettleException
	{
		int retval = 0;

    RowMetaAndData par = repository.connectionDelegate.getParameterMetaData(id_transformation);
		String sql = "SELECT COUNT(*) FROM "+quoteTable(KettleDatabaseRepository.TABLE_R_TRANS_HOP)+" WHERE "+quote(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_TRANSFORMATION)+" = ? ";
		RowMetaAndData r = repository.connectionDelegate.getOneRow(sql, par.getRowMeta(), par.getData());
		if (r != null)
		{
			retval = (int) r.getInteger(0, 0L);
		}

		return retval;
	}


	public synchronized int getNrTransDependencies(ObjectId id_transformation) throws KettleException
	{
		int retval = 0;

    RowMetaAndData par = repository.connectionDelegate.getParameterMetaData(id_transformation);
		String sql = "SELECT COUNT(*) FROM "+quoteTable(KettleDatabaseRepository.TABLE_R_DEPENDENCY)+" WHERE "+quote(KettleDatabaseRepository.FIELD_DEPENDENCY_ID_TRANSFORMATION)+" = ? ";
		RowMetaAndData r = repository.connectionDelegate.getOneRow(sql, par.getRowMeta(), par.getData());
		if (r != null)
		{
			retval = (int) r.getInteger(0, 0L);
		}

		return retval;
	}

	public String[] getTransformationsWithIDList(List<Object[]> list, RowMetaInterface rowMeta) throws KettleException
    {
        String[] transList = new String[list.size()];
        for (int i=0;i<list.size();i++)
        {
            long id_transformation = rowMeta.getInteger( list.get(i), quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_TRANSFORMATION), -1L); 
            if (id_transformation > 0)
            {
                RowMetaAndData transRow =  getTransformation(new LongObjectId(id_transformation));
                if (transRow!=null)
                {
                    String transName = transRow.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_NAME, "<name not found>");
                    long id_directory = transRow.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY, -1L);
                    RepositoryDirectoryInterface dir = repository.loadRepositoryDirectoryTree().findDirectory(new LongObjectId(id_directory));
                    
                    transList[i]=dir.getPathObjectCombination(transName);
                }
            }            
        }

        return transList;
    }

	 public String[] getTransformationsWithIDList(ObjectId[] ids) throws KettleException
   {
       String[] transList = new String[ids.length];
       for (int i=0;i<ids.length;i++)
       {
           ObjectId id_transformation = ids[i]; 
           if (id_transformation != null)
           {
               RowMetaAndData transRow =  getTransformation(id_transformation);
               if (transRow!=null)
               {
                 String transName = transRow.getString(KettleDatabaseRepository.FIELD_TRANSFORMATION_NAME, "<name not found>");
                 long id_directory = transRow.getInteger(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY, -1L);
                 RepositoryDirectoryInterface dir = repository.loadRepositoryDirectoryTree().findDirectory(new LongObjectId(id_directory));
                 if(dir!=null)
                 transList[i]=dir.getPathObjectCombination(transName);
               }
           }            
       }

       return transList;
   }
	 
	public boolean existsTransMeta(String transname, RepositoryDirectory directory) throws KettleException {
		return getTransformationID(transname, directory.getObjectId()) != null;
	}
	
    public ObjectId[] getTransHopIDs(ObjectId id_transformation) throws KettleException
	{
		return repository.connectionDelegate.getIDs("SELECT "+quote(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_TRANS_HOP)+" FROM "+quoteTable(KettleDatabaseRepository.TABLE_R_TRANS_HOP)+" WHERE "+quote(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_TRANSFORMATION)+" = ? ", id_transformation);
	}

	private synchronized void insertTransformation(TransMeta transMeta) throws KettleException
    {
		RowMetaAndData table = new RowMetaAndData();
		// bug 20701 edit by cli 1997-1110
		RowMetaAndData userResourceTable = new RowMetaAndData();
		userResourceTable.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_C_USER_ID, ValueMetaInterface.TYPE_INTEGER), new LongObjectId(Integer.parseInt(repository.getRepositoryMeta().getUserID())));
		userResourceTable.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_C_RESOURCE_ID, ValueMetaInterface.TYPE_INTEGER), new LongObjectId(transMeta.getObjectId()));
		userResourceTable.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_C_RESOURCE_TYPE_ID, ValueMetaInterface.TYPE_INTEGER), new LongObjectId(3));
		
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_TRANSFORMATION, ValueMetaInterface.TYPE_INTEGER), new LongObjectId(transMeta.getObjectId()));
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_NAME, ValueMetaInterface.TYPE_STRING), transMeta.getName());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_DESCRIPTION, ValueMetaInterface.TYPE_STRING), transMeta.getDescription());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_EXTENDED_DESCRIPTION, ValueMetaInterface.TYPE_STRING), transMeta.getExtendedDescription());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_TRANS_VERSION, ValueMetaInterface.TYPE_STRING), transMeta.getTransversion());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_TRANS_STATUS, ValueMetaInterface.TYPE_INTEGER), new Long(transMeta.getTransstatus()  <0 ? -1L : transMeta.getTransstatus()));
		TransLogTable logTable = transMeta.getTransLogTable();
		StepMeta step = (StepMeta) logTable.getSubject(TransLogTable.ID.LINES_READ);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_READ, ValueMetaInterface.TYPE_INTEGER), step==null ? null : step.getObjectId());
		step = (StepMeta) logTable.getSubject(TransLogTable.ID.LINES_WRITTEN);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_WRITE, ValueMetaInterface.TYPE_INTEGER), step==null ? null : step.getObjectId());
		step = (StepMeta) logTable.getSubject(TransLogTable.ID.LINES_INPUT);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_INPUT, ValueMetaInterface.TYPE_INTEGER), step==null ? null : step.getObjectId());
		step = (StepMeta) logTable.getSubject(TransLogTable.ID.LINES_OUTPUT);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_OUTPUT, ValueMetaInterface.TYPE_INTEGER), step==null ? null : step.getObjectId());
		step = (StepMeta) logTable.getSubject(TransLogTable.ID.LINES_UPDATED);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_STEP_UPDATE, ValueMetaInterface.TYPE_INTEGER), step==null ? null : step.getObjectId());
		
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DATABASE_LOG, ValueMetaInterface.TYPE_INTEGER), logTable.getDatabaseMeta()==null ? new LongObjectId(-1L).longValue() : new LongObjectId(logTable.getDatabaseMeta().getObjectId()).longValue());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_TABLE_NAME_LOG, ValueMetaInterface.TYPE_STRING), logTable.getDatabaseMeta());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_USE_BATCHID, ValueMetaInterface.TYPE_BOOLEAN), Boolean.valueOf(logTable.isBatchIdUsed()));
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_USE_LOGFIELD, ValueMetaInterface.TYPE_BOOLEAN), Boolean.valueOf(logTable.isLogFieldUsed()));
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DATABASE_MAXDATE, ValueMetaInterface.TYPE_INTEGER), transMeta.getMaxDateConnection()==null ? new LongObjectId(-1L).longValue() : new LongObjectId(transMeta.getMaxDateConnection().getObjectId()).longValue());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_TABLE_NAME_MAXDATE, ValueMetaInterface.TYPE_STRING), transMeta.getMaxDateTable());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_FIELD_NAME_MAXDATE, ValueMetaInterface.TYPE_STRING), transMeta.getMaxDateField());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_OFFSET_MAXDATE, ValueMetaInterface.TYPE_NUMBER), new Double(transMeta.getMaxDateOffset()));
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_DIFF_MAXDATE, ValueMetaInterface.TYPE_NUMBER), new Double(transMeta.getMaxDateDifference()));

		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_CREATED_USER, ValueMetaInterface.TYPE_STRING),        transMeta.getCreatedUser());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_CREATED_DATE, ValueMetaInterface.TYPE_DATE), transMeta.getCreatedDate());
		
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_MODIFIED_USER, ValueMetaInterface.TYPE_STRING), transMeta.getModifiedUser());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_MODIFIED_DATE, ValueMetaInterface.TYPE_DATE), transMeta.getModifiedDate());
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_SIZE_ROWSET, ValueMetaInterface.TYPE_INTEGER), new Long(transMeta.getSizeRowset()));
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY, ValueMetaInterface.TYPE_INTEGER), transMeta.getRepositoryDirectory().getObjectId());

		repository.connectionDelegate.getDatabase().prepareInsert(table.getRowMeta(), KettleDatabaseRepository.TABLE_R_TRANSFORMATION);
		repository.connectionDelegate.getDatabase().setValuesInsert(table);
		repository.connectionDelegate.getDatabase().insertRow();
		repository.connectionDelegate.getDatabase().closeInsert();
		// bug 20701 edit by cli 1142-1146
		repository.connectionDelegate.getDatabase().prepareInsert(userResourceTable.getRowMeta(), KettleDatabaseRepository.TABLE_KDI_T_USER_RESOURCE);
		repository.connectionDelegate.getDatabase().setValuesInsert(userResourceTable);
		repository.connectionDelegate.getDatabase().insertRow();
		repository.connectionDelegate.getDatabase().closeInsert();
		
		step = (StepMeta) logTable.getSubject(TransLogTable.ID.LINES_REJECTED);
        if (step!=null)
        {
        	ObjectId rejectedId = step.getObjectId();
            repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_ID_STEP_REJECTED, rejectedId==null ? null : Long.valueOf(rejectedId.toString()), null);
        }

        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_UNIQUE_CONNECTIONS, 0, transMeta.isUsingUniqueConnections()?"Y":"N");
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_FEEDBACK_SHOWN, 0, transMeta.isFeedbackShown()?"Y":"N");
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_FEEDBACK_SIZE, transMeta.getFeedbackSize(), "");
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_USING_THREAD_PRIORITIES, 0, transMeta.isUsingThreadPriorityManagment()?"Y":"N");
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_SHARED_FILE, 0, transMeta.getSharedObjectsFile());
        
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_CAPTURE_STEP_PERFORMANCE, 0, transMeta.isCapturingStepPerformanceSnapShots()?"Y":"N");
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_STEP_PERFORMANCE_CAPTURING_DELAY, transMeta.getStepPerformanceCapturingDelay(), "");
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_STEP_PERFORMANCE_CAPTURING_SIZE_LIMIT, 0, transMeta.getStepPerformanceCapturingSizeLimit());
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_STEP_PERFORMANCE_LOG_TABLE, 0, transMeta.getPerformanceLogTable().getTableName());

        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_LOG_SIZE_LIMIT, 0, transMeta.getTransLogTable().getLogSizeLimit());
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_LOG_INTERVAL, 0, transMeta.getTransLogTable().getLogInterval());
        repository.connectionDelegate.insertTransAttribute(transMeta.getObjectId(), 0, KettleDatabaseRepository.TRANS_ATTRIBUTE_TRANSFORMATION_TYPE, 0, transMeta.getTransformationType().getCode());

		// Save the logging connection link...
		if (logTable.getDatabaseMeta()!=null) repository.insertStepDatabase(transMeta.getObjectId(), null, logTable.getDatabaseMeta().getObjectId());

		// Save the maxdate connection link...
		if (transMeta.getMaxDateConnection()!=null) repository.insertStepDatabase(transMeta.getObjectId(), null, transMeta.getMaxDateConnection().getObjectId());
		
	    // Save the logging tables too..
	    //
		RepositoryAttributeInterface attributeInterface = new KettleDatabaseRepositoryTransAttribute(repository.connectionDelegate, transMeta.getObjectId());
	    transMeta.getTransLogTable().saveToRepository(attributeInterface);
	    transMeta.getStepLogTable().saveToRepository(attributeInterface);
	    transMeta.getPerformanceLogTable().saveToRepository(attributeInterface);
	    transMeta.getChannelLogTable().saveToRepository(attributeInterface);		
	}

	private synchronized ObjectId insertTransHop(ObjectId id_transformation, ObjectId id_step_from, ObjectId id_step_to, boolean enabled) throws KettleException {
		ObjectId id = repository.connectionDelegate.getNextTransHopID();

		RowMetaAndData table = new RowMetaAndData();

		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_TRANS_HOP, ValueMetaInterface.TYPE_INTEGER), id);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_TRANSFORMATION, ValueMetaInterface.TYPE_INTEGER), id_transformation);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_STEP_FROM, ValueMetaInterface.TYPE_INTEGER), id_step_from);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANS_HOP_ID_STEP_TO, ValueMetaInterface.TYPE_INTEGER), id_step_to);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANS_HOP_ENABLED, ValueMetaInterface.TYPE_BOOLEAN), Boolean.valueOf(enabled));

		repository.connectionDelegate.getDatabase().prepareInsert(table.getRowMeta(), KettleDatabaseRepository.TABLE_R_TRANS_HOP);
		repository.connectionDelegate.getDatabase().setValuesInsert(table);
		repository.connectionDelegate.getDatabase().insertRow();
		repository.connectionDelegate.getDatabase().closeInsert();

		return id;
	}


	private synchronized ObjectId insertDependency(ObjectId id_transformation, ObjectId id_database, String tablename, String fieldname) throws KettleException
	{
		ObjectId id = repository.connectionDelegate.getNextDepencencyID();

		RowMetaAndData table = new RowMetaAndData();

		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_DEPENDENCY_ID_DEPENDENCY, ValueMetaInterface.TYPE_INTEGER), id);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_DEPENDENCY_ID_TRANSFORMATION, ValueMetaInterface.TYPE_INTEGER), id_transformation);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_DEPENDENCY_ID_DATABASE, ValueMetaInterface.TYPE_INTEGER), id_database);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_DEPENDENCY_TABLE_NAME, ValueMetaInterface.TYPE_STRING), tablename);
		table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_DEPENDENCY_FIELD_NAME, ValueMetaInterface.TYPE_STRING), fieldname);

		repository.connectionDelegate.getDatabase().prepareInsert(table.getRowMeta(), KettleDatabaseRepository.TABLE_R_DEPENDENCY);
		repository.connectionDelegate.getDatabase().setValuesInsert(table);
		repository.connectionDelegate.getDatabase().insertRow();
		repository.connectionDelegate.getDatabase().closeInsert();

		return id;
	}

	public boolean getTransAttributeBoolean(ObjectId id_transformation, int nr, String code) throws KettleException {
		return repository.connectionDelegate.getTransAttributeBoolean(id_transformation, nr, code);
	}
	public String getTransAttributeString(ObjectId id_transformation, int nr, String code) throws KettleException {
		return repository.connectionDelegate.getTransAttributeString(id_transformation, nr, code);
	}
	public long getTransAttributeInteger(ObjectId id_transformation, int nr, String code) throws KettleException {
		return repository.connectionDelegate.getTransAttributeInteger(id_transformation, nr, code);
	}	

	
	public SharedObjects readTransSharedObjects(TransMeta transMeta) throws KettleException {
		
    	transMeta.setSharedObjectsFile( getTransAttributeString(transMeta.getObjectId(), 0, "SHARED_FILE") );
    	
    	transMeta.setSharedObjects( transMeta.readSharedObjects() );
    	
    	// Repository objects take priority so let's overwrite them...
    	//
        readDatabases(transMeta, true);
        readPartitionSchemas(transMeta, true);
        readSlaves(transMeta, true);
        readClusters(transMeta, true);
        readCacheFiles(transMeta,true);
        
        return transMeta.getSharedObjects();
    }

	
	public synchronized void moveTransformation(String transname, ObjectId id_directory_from, ObjectId id_directory_to) throws KettleException
	{
        String nameField = quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_NAME);
		String sql = "UPDATE "+quoteTable(KettleDatabaseRepository.TABLE_R_TRANSFORMATION)+" SET "+quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY)+" = ? WHERE "+nameField+" = ? AND "+quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY)+" = ?";

		RowMetaAndData par = new RowMetaAndData();
		par.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY, ValueMetaInterface.TYPE_INTEGER), id_directory_to);
		par.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_NAME,  ValueMetaInterface.TYPE_STRING), transname);
		par.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY, ValueMetaInterface.TYPE_INTEGER), id_directory_from);

		repository.connectionDelegate.getDatabase().execStatement(sql, par.getRowMeta(), par.getData());
	}

	public synchronized void renameTransformation(ObjectId id_transformation, RepositoryDirectoryInterface newParentDir, String newname) throws KettleException
	{
	  if(newParentDir != null || newname != null) {
      RowMetaAndData table = new RowMetaAndData();
      String sql = "UPDATE " + quoteTable(KettleDatabaseRepository.TABLE_R_TRANSFORMATION) + " SET ";
      
      boolean additionalParameter = false;
      
      if(newname != null) {
        additionalParameter = true;
        sql += quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_NAME) + " = ? ";
        table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_NAME,  ValueMetaInterface.TYPE_STRING), newname);
      }
      
      if(newParentDir != null) {
        if(additionalParameter) {
          sql += ", ";
        }
        sql += quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY) + " = ? ";
        table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_DIRECTORY,  ValueMetaInterface.TYPE_INTEGER), newParentDir.getObjectId());
      }
      
      sql += "WHERE " + quote(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_TRANSFORMATION) + " = ?";
      table.addValue(new ValueMeta(KettleDatabaseRepository.FIELD_TRANSFORMATION_ID_TRANSFORMATION,  ValueMetaInterface.TYPE_INTEGER), id_transformation);
      
      log.logBasic("sql = [" + sql + "]");
      log.logBasic("row = [" + table + "]");
      
      repository.connectionDelegate.getDatabase().execStatement(sql, table.getRowMeta(), table.getData());
      repository.connectionDelegate.getDatabase().commit();
	  }
	}
}
