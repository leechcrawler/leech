/*
 * Leech - crawling capabilities for Apache Tika
 * 
 * Copyright (C) 2012 DFKI GmbH, Author: Christian Reuschling
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contact us by mail: christian.reuschling@dfki.de
 */

package de.dfki.km.leech.lucene;



import java.io.File;
import java.io.IOException;
import java.rmi.server.UID;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.SimpleFSDirectory;
import org.apache.lucene.util.Version;
import org.apache.tika.metadata.Metadata;

import de.dfki.km.leech.FileUtils;
import de.dfki.km.leech.Leech;
import de.dfki.km.leech.metadata.LeechMetadata;
import de.dfki.km.leech.parser.incremental.IncrementalCrawlingHistory;
import de.dfki.km.leech.sax.DataSinkContentHandler;
import de.dfki.km.leech.util.MultiValueHashMap;



/**
 * This is a content handler that allows to store crawled data into a Lucene index. You are able to configure the field types and the analyzers that
 * should be used. Further, blockindexing with {@link IndexWriter#addDocuments(java.util.Collection, Analyzer)} is supported, you can enable it with
 * {@link ToLuceneContentHandler#setBlockIndexing(boolean)}. If it is enabled, {@link ToLuceneContentHandler} checks whether inside the metadata is a
 * {@link LeechMetadata#childId} or a {@link LeechMetadata#parentId} key. Documents with a {@link LeechMetadata#childId} entry will appear as parent
 * documents, docs with an {@link LeechMetadata#parentId} as childs. {@link ToLuceneContentHandler} collects the child documents if they appear at a
 * processXXX method, and writes them as block at the time a succeeding parent document appears. In the case a non-parent doc appears, all collected
 * docs will be indexed normally, not as block.
 * 
 * @author Christian Reuschling, Dipl.Ing.(BA)
 * 
 */
public class ToLuceneContentHandler extends DataSinkContentHandler
{


    protected class DocConsumer implements Runnable
    {

        @Override
        public void run()
        {
            try
            {
                while (true)
                {

                    List<Document> llDocs = m_addDocsQueue.take();


                    if(llDocs.size() == 1)
                    {
                        m_luceneWriter.addDocument(llDocs.get(0));
                    }
                    else if(llDocs.size() > 1)
                    {
                        m_luceneWriter.addDocuments(llDocs);
                    }


                }
            }
            catch (InterruptedException e)
            {
                // NOP
            }
            catch (Exception e)
            {
                Logger.getLogger(ToLuceneContentHandler.DocConsumer.class.getName()).log(Level.SEVERE, "Error", e);
            }

        }
    }



    protected final BlockingQueue<List<Document>> m_addDocsQueue = new LinkedBlockingQueue<List<Document>>(23);



    protected boolean m_bBlockIndexing = true;



    protected FieldConfig m_fieldConfig = new FieldConfig();







    protected HashSet<String> m_hsAttNamesNot2Store = new HashSet<String>();



    protected Map<String, String> m_hsFieldName2FieldValueConstraint;





    protected MultiValueHashMap<String, String> m_hsSource2TargetFieldnames = new MultiValueHashMap<String, String>();

    protected MultiValueHashMap<String, String> m_hsStaticAttValuePairs = new MultiValueHashMap<String, String>();

    protected MultiValueHashMap<String, String> m_hsTarget2SourcesFieldnames = new MultiValueHashMap<String, String>();


    protected IndexWriter m_initialLuceneWriter;



    protected int m_iSplitIndexDocumentCount = 500000;


    protected LinkedList<Thread> m_llConsumerThreads = new LinkedList<Thread>();



    protected LinkedList<Document> m_llLastChildDocuments = new LinkedList<Document>();




    protected HashSet<String> m_hsTmpLuceneWriterPaths2Merge = new HashSet<String>();



    protected IndexWriter m_luceneWriter;



    public ToLuceneContentHandler(FieldConfig fieldConfig, IndexWriter luceneWriter) throws Exception
    {
        super();
        m_fieldConfig = fieldConfig;
        m_luceneWriter = luceneWriter;
        m_initialLuceneWriter = m_luceneWriter;

        init();
    }



    public ToLuceneContentHandler(int writeLimit, FieldConfig fieldConfig, IndexWriter luceneWriter) throws Exception
    {
        super(writeLimit);
        m_fieldConfig = fieldConfig;
        m_luceneWriter = luceneWriter;
        m_initialLuceneWriter = m_luceneWriter;

        init();
    }



    public ToLuceneContentHandler(Metadata metadata, FieldConfig fieldConfig, IndexWriter luceneWriter) throws Exception
    {
        super(metadata);
        m_fieldConfig = fieldConfig;
        m_luceneWriter = luceneWriter;
        m_initialLuceneWriter = m_luceneWriter;

        init();
    }



    public ToLuceneContentHandler(Metadata metadata, int writeLimit, FieldConfig fieldConfig, IndexWriter luceneWriter) throws Exception
    {
        super(metadata, writeLimit);
        m_fieldConfig = fieldConfig;
        m_luceneWriter = luceneWriter;
        m_initialLuceneWriter = m_luceneWriter;

        init();
    }



    protected void addStaticAttValuePairs(Document doc) throws Exception
    {
        for (Entry<String, String> fieldName2Value : getStaticAttributeValuePairs().entryList())
            doc.add(FieldFactory.createField(fieldName2Value.getKey(), fieldName2Value.getValue(), m_fieldConfig));
    }



    /**
     * Will merge all temporar indices together into the initial indexWriter index. This is only necessary if SplitAndMerge is enabled. Otherwise you
     * don't have to invoke this method.
     */
    @Override
    public void crawlFinished()
    {
        try
        {

            for (Thread consumerThread : m_llConsumerThreads)
                consumerThread.interrupt();

            if(getSplitAndMergeIndex() <= 0) return;

            // hier mergen wir nun alle temporären indices in den originalen
            
            //der letzte temporäre konnte noch nicht geschlossen werden - das machen wir jetzt
            if(m_luceneWriter != m_initialLuceneWriter) m_luceneWriter.close();

            LinkedList<Directory> llIndicesDirs2Merge = new LinkedList<Directory>();

            for (String strTmpPath : m_hsTmpLuceneWriterPaths2Merge)
                llIndicesDirs2Merge.add(new SimpleFSDirectory(new File(strTmpPath)));

            if(llIndicesDirs2Merge.size() == 0) return;

            Logger.getLogger(ToLuceneContentHandler.class.getName()).info(
                    "Will merge " + llIndicesDirs2Merge.size() + " temporary indices to the final one.");

            
            m_initialLuceneWriter.addIndexes(llIndicesDirs2Merge.toArray(new Directory[0]));

            m_initialLuceneWriter.commit();

            for (String strTmpPath : m_hsTmpLuceneWriterPaths2Merge)
                FileUtils.deleteDirectory(new File(strTmpPath));


        }
        catch (Exception e)
        {
            Logger.getLogger(ToLuceneContentHandler.class.getName()).log(Level.SEVERE, "Error", e);
        }

    }



    /**
     * Returns null in the case the documents should be ignored according the given constraints (given with {@link #setIgnoreAllDocsWithout(Map)})
     * 
     * @param metadata
     * @param strFulltext
     * 
     * @return null in the case the documents should be ignored according the given constraints (given with {@link #setIgnoreAllDocsWithout(Map)})
     * 
     * @throws Exception
     */
    protected Document createAndFillLuceneDocument(Metadata metadata, String strFulltext) throws Exception
    {
        // // wir erstellen kein Document-Object neu, wenn es nicht unbedingt nötig ist - dazu merken wir uns die Referenzen auf die schon allokierten
        // // Document Objekte
        // // Document Object reuse
        // Document doc = null;
        // for (Document preAllocatedDoc : m_llAllocatedDocuments)
        // {
        // if(!m_llLastChildDocuments.contains(preAllocatedDoc))
        // {
        // doc = preAllocatedDoc;
        // LinkedList<String> llFieldNames = new
        // for (Fieldable field : doc.getFields())
        // doc.removeFields(field.name());
        //
        // break;
        // }
        // }
        // if(doc == null)
        // {
        // doc = new Document();
        // m_llAllocatedDocuments.add(doc);
        // }

        Document doc = new Document();



        // Das man kein Field aus einem reader machen kann ist der Grund, warum processNewMetaData den Fulltext als String und nicht als reader
        // übergibt

        //eine eindeutige ID muß da sein
        if(metadata.getValues(LeechMetadata.id).length == 0) doc.add(FieldFactory.createField(LeechMetadata.id, new UID().toString(), m_fieldConfig));
        if(!getFields2Ignore().contains(LeechMetadata.body)) doc.add(FieldFactory.createField(LeechMetadata.body, strFulltext, m_fieldConfig));
        // die kopien
        for (String strFieldCopy : getFieldCopyMap().get(LeechMetadata.body))
            if(!getFields2Ignore().contains(strFieldCopy)) doc.add(FieldFactory.createField(strFieldCopy, strFulltext, m_fieldConfig));


        // die restlichen metadaten
        for (String strFieldName : metadata.names())
        {
            if(!getFields2Ignore().contains(strFieldName))
            {
                for (String strValue : metadata.getValues(strFieldName))
                    doc.add(FieldFactory.createField(strFieldName, strValue, m_fieldConfig));
            }

            // die kopien
            for (String strFieldCopy : getFieldCopyMap().get(strFieldName))
                if(!getFields2Ignore().contains(strFieldCopy))
                {
                    for (String strValue : metadata.getValues(strFieldName))
                        doc.add(FieldFactory.createField(strFieldCopy, strValue, m_fieldConfig));
                }
        }

        // die statischen Attribut-Value-Paare
        addStaticAttValuePairs(doc);

        // und jetzt aggregieren wir noch
        for (String strTargetAtt : getFieldAggregationMap().keySet())
        {
            // wenn es das TargetAtt schon im doc gibt, dann aggregieren wir nix
            if(doc.get(strTargetAtt) != null) continue;

            Collection<String> colSourceAtts = getFieldAggregationMap().get(strTargetAtt);

            for (String strSourceAtt : colSourceAtts)
            {
                String strNewValue = metadata.get(strSourceAtt);
                if(strNewValue == null) strNewValue = getStaticAttributeValuePairs().getFirst(strSourceAtt);

                if(strNewValue != null)
                {
                    doc.add(FieldFactory.createField(strTargetAtt, strNewValue, m_fieldConfig));

                    break;
                }
            }
        }



        // wenn ein Doc nicht unseren constraints entspricht, dann ignorieren wir das hier, indem wir null zurück geben
        if(m_hsFieldName2FieldValueConstraint == null || m_hsFieldName2FieldValueConstraint.size() == 0) return doc;

        for (Entry<String, String> fieldname2fieldValRegEx : m_hsFieldName2FieldValueConstraint.entrySet())
        {
            Fieldable[] fieldables = doc.getFieldables(fieldname2fieldValRegEx.getKey());
            for (Fieldable fieldable : fieldables)
            {
                String strVal = fieldable.stringValue();
                if(strVal.matches(fieldname2fieldValRegEx.getValue()))
                {
                    // wir haben einen Treffer
                    return doc;
                }
            }
        }


        return null;
    }



    public boolean getBlockIndexing()
    {
        return m_bBlockIndexing;
    }



    protected IndexWriter getCurrentWriter() throws CorruptIndexException, LockObtainFailedException, IOException
    {
        if(getSplitAndMergeIndex() <= 0) return m_initialLuceneWriter;

        if(m_luceneWriter.maxDoc() < getSplitAndMergeIndex()) return m_luceneWriter;


        Directory directory = m_initialLuceneWriter.getDirectory();

        File fOurTmpDir = null;
        if(directory instanceof FSDirectory)
        {
            if(m_luceneWriter != m_initialLuceneWriter) m_luceneWriter.close();

            String strTmpPath = ((FSDirectory) directory).getDirectory().getAbsolutePath();
            // if(strTmpPath.charAt(strTmpPath.length() - 1) == '/' || strTmpPath.charAt(strTmpPath.length() - 1) == '\\')
            // strTmpPath = strTmpPath.substring(0, strTmpPath.length() - 1);
            strTmpPath += "_" + (m_hsTmpLuceneWriterPaths2Merge.size() + 1);
            fOurTmpDir = new File(strTmpPath);
        }
        else
        {
            // wir brauchen was temporäres
            File parentDir = File.createTempFile("Hitzli", "Putzli").getParentFile();
            fOurTmpDir = new File(parentDir.getAbsolutePath() + "/leechTmp/" + UUID.randomUUID().toString().replaceAll("\\W", "_"));
        }

        Logger.getLogger(ToLuceneContentHandler.class.getName()).info(
                "Current intex exceeds " + m_iSplitIndexDocumentCount + " documents. Will create another temporary one under " + fOurTmpDir);


        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_CURRENT, m_initialLuceneWriter.getConfig().getAnalyzer());
        config.setOpenMode(OpenMode.CREATE);

        m_luceneWriter = new IndexWriter(new SimpleFSDirectory(fOurTmpDir), config);
        m_hsTmpLuceneWriterPaths2Merge.add(fOurTmpDir.getAbsolutePath());


        return m_luceneWriter;
    }



    /**
     * Gets the field aggregation map. This means that you want to generate a field entry, whereby its value should be copied from another, existing
     * metadata entry. You can specify a list of these source-attributes, the first who have an entry wins and appears as new attribute, so the source
     * field name list is in fact a priorized list.
     * 
     * @return the current field aggregation map
     */
    public MultiValueHashMap<String, String> getFieldAggregationMap()
    {
        return m_hsTarget2SourcesFieldnames;
    }



    /**
     * Gets the field copy mappings. This means that the content of every metadata key that is specified as key inside hsSource2TargetFieldnames will
     * be copied into several other fields. The field names of these fields are specified as corresponding value inside hsSource2TargetFieldnames. In
     * the case you want to rename attribute names, specify a field mapping and ignore the source field name with
     * {@link #setFieldNames2Ignore(HashSet)}
     * 
     * @return the current field mappings
     */
    public MultiValueHashMap<String, String> getFieldCopyMap()
    {
        return m_hsSource2TargetFieldnames;
    }



    /**
     * Gets the set of field names / metadata key values that will NOT be stored into the lucene index.
     * 
     * @return the set of field names / metadata key values that will NOT be stored into the lucene index.
     */
    public HashSet<String> getFields2Ignore()
    {
        return m_hsAttNamesNot2Store;
    }



    /**
     * All docs without at least one of the given fieldname-value pairs will be ignored. You can specif regular expressions as field values
     * 
     * @return the fieldname-value pairs. At least one have to match that a document will be written into the index
     */
    public Map<String, String> getIgnoreAllDocsWithout()
    {
        return m_hsFieldName2FieldValueConstraint;
    }



    /**
     * If split and merge is enabled, {@link ToLuceneContentHandler} will check at each {@link #processNewData(Metadata, String)} invocation whether
     * the current indexWriter has more than iSplitIndexDocumentCount documents. In the case it has more, {@link ToLuceneContentHandler} will create
     * an entirely new index for writing, until this one also gets 'overfilled'. In the case your crawl is finished, {@link Leech} invokes
     * {@link ToLuceneContentHandler#crawlFinished()}. This will merge all temporary indices into the initial indexWriter object. This is for
     * performance reasons because writing into a Lucene index tends to get slow after a certain size. Splitting and merging afterwards is faster.
     * 
     * @return the document count a new index will be created
     */
    public int getSplitAndMergeIndex()
    {
        return m_iSplitIndexDocumentCount;
    }



    /**
     * Sets some attribute value pairs that will be added to every crawled document.
     * 
     * @return the current static attribute value pairs
     */
    public MultiValueHashMap<String, String> getStaticAttributeValuePairs()
    {
        return m_hsStaticAttValuePairs;
    }



    @Override
    protected void init()
    {
        Logger.getLogger(ToLuceneContentHandler.class.getName()).info("Will write crawled data into " + m_luceneWriter.getDirectory().toString());

        int iCoreCount = Runtime.getRuntime().availableProcessors();
        long iThreadCount = Math.round(iCoreCount / 2d);

        for (int i = 0; i < iThreadCount; i++)
        {
            Thread consumerThread = new Thread(new DocConsumer(), "ToLuceneContentHandlerDocConsumer " + i);
            m_llConsumerThreads.add(consumerThread);
            consumerThread.setDaemon(true);

            consumerThread.start();
        }
    }



    @Override
    public void processErrorData(Metadata metadata)
    {
        // NOP
    }




    @Override
    public void processModifiedData(Metadata metadata, String strFulltext)
    {

        try
        {

            // hier modifizieren wir ein schon vorhandenes Dokument
            Document luceneDocument = createAndFillLuceneDocument(metadata, strFulltext);
            if(luceneDocument == null) return;



            // TODO: was passiert hier mit block-indexierten Dokumenten?
            m_initialLuceneWriter.updateDocument(
                    new Term(IncrementalCrawlingHistory.dataEntityExistsID, metadata.get(IncrementalCrawlingHistory.dataEntityExistsID)),
                    luceneDocument);

        }
        catch (Exception e)
        {
            Logger.getLogger(ToLuceneContentHandler.class.getName()).log(Level.SEVERE, "Error during writing into the index", e);
        }

    }



    @Override
    public void processNewData(Metadata metadata, String strFulltext)
    {

        try
        {
            if(m_initialLuceneWriter == null) throw new IllegalStateException("Lucene writer was not specified");

            m_luceneWriter = getCurrentWriter();



            Document doc = createAndFillLuceneDocument(metadata, strFulltext);
            if(doc == null) return;



            // wenn es ein parent oder childDoc ist, dann merken wir uns dieses erst mal, bis wir einen ganzen Block haben. Wenn wir auf ein childDoc
            // stossen, dann schreiben wir beim nächsten parendDoc, und merken uns alle childs bis dahin
            // - wenn wir auf ein Doc ohne parent-oder child-Id stossen, dann schreiben wir alle bisherigen Docs als Einzeldokumente raus - nicht im
            // Block

            if(ToLuceneContentHandler.this.getBlockIndexing())
            {


                if(metadata.get(LeechMetadata.parentId) != null)
                {
                    // wir haben ein child-Doc (wir haben eine Referenz zu unserem parent). Das merken wir uns einfach
                    m_llLastChildDocuments.add(doc);
                }
                else if(metadata.get(LeechMetadata.childId) != null)
                {
                    // wir haben ein parentDoc (ein parent hat min eine childId) - wir schreiben zusammen mit den bisher gesammelten im block. Das
                    // parentDoc ist das letzte
                    m_llLastChildDocuments.add(doc);

                    m_addDocsQueue.put(new LinkedList<Document>(m_llLastChildDocuments));

                    m_llLastChildDocuments.clear();
                }
                else
                {
                    // wir haben weder child-noch parent ID - alle gemerkten childDocs werden als Einzeldocs rausgeschrieben
                    for (Document orphanDoc : m_llLastChildDocuments)
                        m_addDocsQueue.put(Collections.singletonList(orphanDoc));

                    m_addDocsQueue.put(Collections.singletonList(doc));
                }


            }
            else
            {
                m_addDocsQueue.put(Collections.singletonList(doc));
            }





        }
        catch (Exception e)
        {
            Logger.getLogger(ToLuceneContentHandler.class.getName()).log(Level.SEVERE, "Error", e);
        }

    }





    // protected LinkedList<Document> m_llAllocatedDocuments = new LinkedList<Document>();



    @Override
    public void processRemovedData(Metadata metadata)
    {
        // da kann man ja mit den inkremental-Ids spielen, die stehen ja evtl. noch in den Metadaten drin :) :)

        try
        {

            // TODO: was passiert hier mit block-indexierten Dokumenten?
            m_initialLuceneWriter.deleteDocuments(new Term(IncrementalCrawlingHistory.dataEntityExistsID, metadata
                    .get(IncrementalCrawlingHistory.dataEntityExistsID)));

        }
        catch (Exception e)
        {
            Logger.getLogger(ToLuceneContentHandler.class.getName()).log(Level.SEVERE, "Error during writing into the index", e);
        }

    }



    /**
     * Sets whether block indexing with {@link IndexWriter#addDocuments(java.util.Collection, Analyzer)} is enabled or not. If it is enabled,
     * {@link ToLuceneContentHandler} checks whether inside the metadata is a {@link LeechMetadata#childId} or a {@link LeechMetadata#parentId} key.
     * Documents with a {@link LeechMetadata#childId} entry will appear as parent documents, docs with an {@link LeechMetadata#parentId} as childs.
     * {@link ToLuceneContentHandler} collects the child documents if they appear at a processXXX method, and writes them as block at the time a
     * succeeding parent document appears. In the case a non-parent doc appears, all collected docs will be indexed normally, not as block.
     * 
     * @param blockIndexing true in the case blockindexing should be inabled, false otherwise.
     */
    public void setBlockIndexing(boolean blockIndexing)
    {
        this.m_bBlockIndexing = blockIndexing;
    }



    /**
     * Sets the field aggregation map. This means that you want to generate a field entry, whereby its value should be copied from another, existing
     * metadata entry. You can specify a list of these source-attributes, the first who have an entry wins and appears as new attribute, so the source
     * field name list is in fact a priorized list.
     * 
     * @param hsTarget2SourcesFieldnames the field aggregation map
     */
    public void setFieldAggregationMap(MultiValueHashMap<String, String> hsTarget2SourcesFieldnames)
    {
        m_hsTarget2SourcesFieldnames = hsTarget2SourcesFieldnames;
    }




    /**
     * Sets the field copy mappings. This means that the content of every metadata key that is specified as key inside hsSource2TargetFieldnames will
     * be copied into several other fields. The field names of these fields are specified as corresponding value inside hsSource2TargetFieldnames. In
     * the case you want to rename attribute names, specify a field mapping and ignore the source field name with
     * {@link #setFieldNames2Ignore(HashSet)}
     * 
     * @param hsSource2TargetFieldnames keys: source field names, given as metadata keys. values: target field names - the content will also appear
     *            under these fields inside a lucene document
     */
    public void setFieldCopyMap(MultiValueHashMap<String, String> hsSource2TargetFieldnames)
    {
        m_hsSource2TargetFieldnames = hsSource2TargetFieldnames;
    }



    /**
     * Sets the set of field names / metadata key values that will NOT be stored into the lucene index. Nevertheless, you can consider these in
     * {@link #setFieldCopyMap(MultiValueHashMap)}. In this case you have 'moved' the attribute value into another attribute (or several ones).
     * 
     * @param hsAttNamesNot2Store the set of attribute/field names that will not stored into the lucene index
     */
    public void setFieldNames2Ignore(HashSet<String> hsAttNamesNot2Store)
    {
        m_hsAttNamesNot2Store = hsAttNamesNot2Store;
    }



    /**
     * All docs without at least one of the given fieldname-value pairs will be ignored. You can specif regular expressions as field values
     * 
     * @param hsFieldName2FieldValue the fieldname-value pairs. At least one have to match that a document will be written into the index
     * 
     * @return this
     */
    public ToLuceneContentHandler setIgnoreAllDocsWithout(Map<String, String> hsFieldName2FieldValue)
    {
        m_hsFieldName2FieldValueConstraint = hsFieldName2FieldValue;

        return this;
    }




    /**
     * If split and merge is enabled, {@link ToLuceneContentHandler} will check at each {@link #processNewData(Metadata, String)} invocation whether
     * the current indexWriter has more than iSplitIndexDocumentCount documents. In the case it has more, {@link ToLuceneContentHandler} will create
     * an entirely new index for writing, until this one also gets 'overfilled'. In the case your crawl is finished, invoking
     * {@link ToLuceneContentHandler#crawlFinished()} merges all temporary indices into the initial indexWriter object. This invocation will be done
     * automatically by the {@link Leech} class. This is for performance reasons because writing into a Lucene index tends to get slow after a certain
     * size. Splitting and merging afterwards is faster.
     * 
     * @param iSplitIndexDocumentCount the document count a new index will be created. A good size is 500 000 (from my stomach feeling, this is the
     *            default). -1 in the case you want to disable SplitAndMerge.
     * 
     * @return this
     */
    public ToLuceneContentHandler setSplitAndMergeIndex(int iSplitIndexDocumentCount)
    {
        m_iSplitIndexDocumentCount = iSplitIndexDocumentCount;

        return this;
    }







    /**
     * Sets some attribute value pairs that will be added to every crawled document.
     * 
     * @param hsStaticAttValuePairs a multi value map containing the additional attribute value pairs
     */
    public void setStaticAttributeValuePairs(MultiValueHashMap<String, String> hsStaticAttValuePairs)
    {
        m_hsStaticAttValuePairs = hsStaticAttValuePairs;
    }







}
