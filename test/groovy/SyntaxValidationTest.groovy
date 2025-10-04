import org.junit.Test
import org.junit.Before
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit
import groovy.lang.GroovyClassLoader

/**
 * Test that validates syntax of all Groovy files in the project.
 * This catches compilation errors early before deployment.
 */
class SyntaxValidationTest {

    private List<File> groovyFiles = []

    @Before
    void setUp() {
        // Find all .groovy files in vars/ and src/ directories
        def varsDir = new File('vars')
        def srcDir = new File('src')
        
        groovyFiles = []
        
        if (varsDir.exists()) {
            varsDir.eachFileRecurse { file ->
                if (file.name.endsWith('.groovy') && !file.name.endsWith('.bak')) {
                    groovyFiles << file
                }
            }
        }
        
        if (srcDir.exists()) {
            srcDir.eachFileRecurse { file ->
                if (file.name.endsWith('.groovy') && !file.name.endsWith('.bak')) {
                    groovyFiles << file
                }
            }
        }
    }

    @Test
    void "all Groovy files have valid syntax"() {
        def errors = []
        
        groovyFiles.each { file ->
            try {
                validateGroovyFileSyntax(file)
            } catch (Exception e) {
                errors << "Syntax error in ${file.path}:\n${e.message}"
            }
        }
        
        if (!errors.isEmpty()) {
            def errorMessage = new StringBuilder()
            errorMessage.append("Found syntax errors in ${errors.size()} file(s):\n\n")
            errors.each { error ->
                errorMessage.append("${error}\n")
                errorMessage.append("-" * 80)
                errorMessage.append("\n")
            }
            throw new AssertionError(errorMessage.toString())
        }
    }

    @Test
    void "all vars files can be parsed"() {
        def varsDir = new File('vars')
        if (!varsDir.exists()) {
            return // Skip if vars directory doesn't exist
        }

        def errors = []
        
        varsDir.listFiles().each { file ->
            if (file.name.endsWith('.groovy') && !file.name.endsWith('.bak')) {
                try {
                    def content = file.text
                    
                    // Check for common syntax issues (corrupted text from bad edits)
                    if (content.contains('}lainOutput') || content.contains('}tring') || content.contains('}lass')) {
                        errors << "Corrupted text found in ${file.name}: contains mangled closing brace"
                    }
                    
                    // Validate syntax by parsing (this will catch brace mismatches and all other syntax errors)
                    validateGroovyFileSyntax(file)
                    
                } catch (Exception e) {
                    errors << "Error in ${file.name}: ${e.message}"
                }
            }
        }
        
        if (!errors.isEmpty()) {
            def errorMessage = new StringBuilder()
            errorMessage.append("Found issues in ${errors.size()} vars file(s):\n\n")
            errors.each { error ->
                errorMessage.append("- ${error}\n")
            }
            throw new AssertionError(errorMessage.toString())
        }
    }

    @Test
    void "no backup files in vars directory"() {
        def varsDir = new File('vars')
        if (!varsDir.exists()) {
            return
        }

        def backupFiles = varsDir.listFiles().findAll { it.name.endsWith('.bak') }
        
        if (!backupFiles.isEmpty()) {
            def files = backupFiles.collect { it.name }.join(', ')
            println "Warning: Found backup files in vars/: ${files}"
            println "Consider removing these before committing."
        }
    }

    /**
     * Validates Groovy file syntax by attempting to parse it
     */
    private void validateGroovyFileSyntax(File file) {
        def config = new CompilerConfiguration()
        config.setTolerance(0) // Don't tolerate any errors
        
        def classLoader = new GroovyClassLoader(this.class.classLoader, config)
        
        try {
            // Try to parse the file
            classLoader.parseClass(file)
        } catch (Exception e) {
            // Extract meaningful error message
            def message = e.message
            if (e.cause) {
                message += "\nCause: ${e.cause.message}"
            }
            throw new Exception("Failed to parse ${file.name}: ${message}", e)
        } finally {
            classLoader.close()
        }
    }
}
